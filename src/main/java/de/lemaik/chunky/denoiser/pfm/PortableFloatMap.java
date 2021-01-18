package de.lemaik.chunky.denoiser.pfm;

import se.llbit.chunky.resources.BitmapImage;
import se.llbit.math.ColorUtil;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class PortableFloatMap
{
  private static String readLine(InputStream in) throws IOException {
    StringBuilder line = new StringBuilder();
    byte[] buff = new byte[1];
    buff[0] = (byte)in.read();
    while (buff[0] != 0x0a) {
      line.append(new String(buff, StandardCharsets.US_ASCII));
      buff[0] = (byte)in.read();
    }
    return line.toString();
  }

  public static BitmapImage readToRgbImage(InputStream in) throws IOException {
    try (DataInputStream din = new DataInputStream(in)) {
      readLine(din); // skip PF
      String[] size = readLine(din).split(" ");
      int width = Integer.parseInt(size[0]);
      int height = Integer.parseInt(size[1]);
      float scale = Float.parseFloat(readLine(din));

      BitmapImage img = new BitmapImage(width, height);
      byte[] buff = new byte[12];
      for (int y = height-1; y >= 0; y--) {
        for (int x = 0; x < width; x++) {
          din.readFully(buff, 0, 12);
          ByteBuffer buffer = ByteBuffer.wrap(buff).order(scale < 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
          img.setPixel(x, y, ColorUtil.getArgb(buffer.getFloat(0), buffer.getFloat(4), buffer.getFloat(8), 1));
        }
      }

      return img;
    }
  }
}
