package de.lemaik.chunky.denoiser;

import de.lemaik.chunky.denoiser.pfm.PortableFloatMap;
import se.llbit.chunky.PersistentSettings;
import se.llbit.chunky.renderer.RenderContext;
import se.llbit.chunky.renderer.RenderManager;
import se.llbit.chunky.renderer.RenderMode;
import se.llbit.chunky.renderer.RenderStatusListener;
import se.llbit.chunky.renderer.scene.PathTracer;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.chunky.resources.BitmapImage;
import se.llbit.log.Log;
import se.llbit.png.PngFileWriter;
import se.llbit.util.TaskTracker;

import java.io.*;
import java.nio.ByteOrder;

public class BetterRenderManager extends RenderManager {
    public static int ALBEDO_SPP = 16;
    public static int NORMAL_SPP = 16;
    public static boolean ENABLE_ALBEDO = true;
    public static boolean ENABLE_NORMAL = true;
    public static boolean NORMAL_WATER_DISPLACEMENT = true;

    private final RenderContext context;
    private final CombinedRayTracer rayTracer;
    private boolean isFirst = true;
    private RenderMode mode = RenderMode.PREVIEW;

    public BetterRenderManager(RenderContext context, boolean headless, CombinedRayTracer rayTracer) {
        super(context, headless);
        this.context = context;
        this.rayTracer = rayTracer;
        this.addRenderListener(new RenderStatusListener() {
            int oldTargetSpp = 0;

            @Override
            public void setSpp(int spp) {
                if (mode == RenderMode.PREVIEW) {
                    return;
                }

                if (!isFirst && spp >= getBufferedScene().getTargetSpp()) {
                    Scene scene = context.getChunky().getSceneManager().getScene();
                    if (rayTracer.getRayTracer() instanceof NormalTracer) {
                        try (OutputStream out = new BufferedOutputStream(context.getSceneFileOutputStream(scene.name + ".normal.pfm"))) {
                            writeNormalPfmImage(out);
                        } catch (IOException e) {
                            Log.error("Saving the normal PFM failed", e);
                        }
                        if (ENABLE_ALBEDO) {
                            renderAlbedoMap();
                        } else {
                            renderImage(oldTargetSpp);
                        }
                    } else if (rayTracer.getRayTracer() instanceof AlbedoTracer) {
                        try (OutputStream out = new BufferedOutputStream(context.getSceneFileOutputStream(scene.name + ".albedo.pfm"))) {
                            writePfmImage(out, false);
                        } catch (IOException e) {
                            Log.error("Saving the albedo PFM failed", e);
                        }
                        renderImage(oldTargetSpp);
                    } else if (rayTracer.getRayTracer() instanceof PathTracer) {
                        try (OutputStream out = new BufferedOutputStream(context.getSceneFileOutputStream(scene.name + ".pfm"))) {
                          writePfmImage(out, true);
                        } catch (IOException e) {
                          Log.error("Saving the render PFM failed", e);
                        }

                        String denoiserPath = PersistentSettings.settings.getString("oidnPath", null);
                        if (denoiserPath != null) {
                            File denoisedPfm = new File(context.getSceneDirectory(), scene.name + ".denoised.pfm");
                            try {
                                OidnBinaryDenoiser.denoise(denoiserPath,
                                        new File(context.getSceneDirectory(), scene.name + ".pfm"),
                                        ENABLE_ALBEDO ? new File(context.getSceneDirectory(), scene.name + ".albedo.pfm") : null,
                                        ENABLE_NORMAL ? new File(context.getSceneDirectory(), scene.name + ".normal.pfm") : null,
                                        denoisedPfm
                                );
                                BitmapImage img = PortableFloatMap.readToRgbImage(new FileInputStream(denoisedPfm));
                                try (PngFileWriter pngWriter = new PngFileWriter(new File(context.getSceneDirectory(), scene.name + "-" + spp + ".denoised.png"))) {
                                    pngWriter.write(img.data, img.width, img.height, TaskTracker.Task.NONE);
                                }
                            } catch (IOException | InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                } else if (isFirst) {
                    isFirst = false;
                    oldTargetSpp = getBufferedScene().getTargetSpp();
                    if (ENABLE_NORMAL) {
                        renderNormalMap();
                    } else if (ENABLE_ALBEDO) {
                        renderAlbedoMap();
                    } else {
                        renderImage(oldTargetSpp);
                    }
                }
            }

            @Override
            public void setRenderTime(long l) {
            }

            @Override
            public void setSamplesPerSecond(int i) {
            }

            @Override
            public void renderStateChanged(RenderMode renderMode) {
                RenderMode oldMode = BetterRenderManager.this.mode;
                BetterRenderManager.this.mode = renderMode;

                if (renderMode == RenderMode.RENDERING && oldMode != RenderMode.PAUSED) {
                    isFirst = true;
                    oldTargetSpp = context.getChunky().getSceneManager().getScene().getTargetSpp();
                }
            }
        });
    }

    private void renderAlbedoMap() {
        rayTracer.setRayTracer(new AlbedoTracer());
        Scene scene = this.context.getChunky().getSceneManager().getScene();
        scene.haltRender();
        scene.setTargetSpp(ALBEDO_SPP);
        scene.startRender();
    }

    private void renderNormalMap() {
        rayTracer.setRayTracer(new NormalTracer());
        Scene scene = context.getChunky().getSceneManager().getScene();
        scene.haltRender();
        scene.setTargetSpp(NORMAL_SPP);
        scene.startRender();

    }

    private void renderImage(int spp) {
        rayTracer.setRayTracer(new PathTracer());
        Scene scene = this.context.getChunky().getSceneManager().getScene();
        scene.haltRender();
        scene.setTargetSpp(spp);
        scene.startRender();
    }

    private void writePfmImage(OutputStream out, boolean postProcess) throws IOException {
        Scene scene = getBufferedScene();
        double[] samples = scene.getSampleBuffer();
        double[] pixels = new double[samples.length];

        for (int y = 0; y < scene.height; y++) {
            for (int x = 0; x < scene.width; x++) {
                double[] result = new double[3];
                if (postProcess) {
                    scene.postProcessPixel(x, y, result);
                } else {
                    result[0] = samples[(y * scene.width + x) * 3 + 0];
                    result[1] = samples[(y * scene.width + x) * 3 + 1];
                    result[2] = samples[(y * scene.width + x) * 3 + 2];
                }
                pixels[(y * scene.width + x) * 3] = Math.min(1.0, result[0]);
                pixels[(y * scene.width + x) * 3 + 1] = Math.min(1.0, result[1]);
                pixels[(y * scene.width + x) * 3 + 2] = Math.min(1.0, result[2]);
            }
        }

        PortableFloatMap.writeImage(pixels, scene.width, scene.height, ByteOrder.LITTLE_ENDIAN, out);
    }

    private void writeNormalPfmImage(OutputStream out) throws IOException {
        Scene scene = getBufferedScene();
        double[] samples = scene.getSampleBuffer();
        double[] pixels = NormalTracer.MAP_POSITIVE ? new double[samples.length] : samples;
        if (NormalTracer.MAP_POSITIVE) {
            for (int i = 0; i < samples.length; i++) {
                pixels[i] = Math.min(1.0, samples[i]) * 2 - 1;
            }
        }

        PortableFloatMap.writeImage(pixels, scene.width, scene.height, ByteOrder.LITTLE_ENDIAN, out);
    }
}
