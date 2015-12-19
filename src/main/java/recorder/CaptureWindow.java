package recorder;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.win32.W32APIOptions;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Copyright (c) Nikolay Soloviev. All rights reserved.
 * @author Nikolay Soloviev <psolberger@gmail.com>
 */
public class CaptureWindow {

    private static BufferedImage defaultImage;

    static {
        defaultImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        defaultImage.setRGB(0, 0, new Color(127,127,127).getRGB());
    }

    public BufferedImage capture(HWND hWnd, int width, int height) {
        BufferedImage image;

        try {
            HDC hdcWindow = User32.INSTANCE.GetDC(hWnd);
            HDC hdcMemDC = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow);

            HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, width, height);

            HANDLE hOld = GDI32.INSTANCE.SelectObject(hdcMemDC, hBitmap);
            GDI32Extra.INSTANCE.BitBlt(hdcMemDC, 0, 0, width, height, hdcWindow, 0, 0, WinGDIExtra.SRCCOPY);

            GDI32.INSTANCE.SelectObject(hdcMemDC, hOld);
            GDI32.INSTANCE.DeleteDC(hdcMemDC);

            BITMAPINFO bmi = new BITMAPINFO();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

            Memory buffer = new Memory(width * height * 4);
            GDI32.INSTANCE.GetDIBits(hdcWindow, hBitmap, 0, height, buffer, bmi, WinGDI.DIB_RGB_COLORS);

            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, width, height, buffer.getIntArray(0, width * height), 0, width);

            GDI32.INSTANCE.DeleteObject(hBitmap);
            User32.INSTANCE.ReleaseDC(hWnd, hdcWindow);
        }
        catch (Exception e) {
            image = defaultImage;
        }

        return image;
    }

    public BufferedImage capture(HWND[] hWnds) {
        BufferedImage image;
        BufferedImage newImage;
        WinUser.RECT[] windowsRect = new WinUser.RECT[hWnds.length];
        int maxWidth = 1;
        int maxHeight = 1;

        for (int i = hWnds.length-1; i >= 0; i--) {
            WinUser.WINDOWINFO windowInfo = new WinUser.WINDOWINFO();

            User32.INSTANCE.GetWindowInfo(hWnds[i], windowInfo);
            windowsRect[i] = windowInfo.rcClient;

            int width = windowsRect[i].right - windowsRect[i].left;
            int height = windowsRect[i].bottom - windowsRect[i].top;

            if (width < 1 || height < 1) {
                continue;
            }

            if(windowsRect[i].right > maxWidth || windowsRect[i].bottom > maxHeight) {
                maxWidth = windowsRect[i].right;
                maxHeight = windowsRect[i].bottom;
            }
        }

        image = new BufferedImage(maxWidth, maxHeight, BufferedImage.TYPE_INT_RGB);

        for (int i = hWnds.length-1; i >= 0; i--) {
            int width = windowsRect[i].right - windowsRect[i].left;
            int height = windowsRect[i].bottom - windowsRect[i].top;

            if (width < 1 || height < 1) {
                continue;
            }

            newImage = capture(hWnds[i], width, height);
            image.getGraphics().drawImage(newImage, windowsRect[i].left, windowsRect[i].top, null);
        }

        return image;
    }

    public interface GDI32Extra extends GDI32 {
        GDI32Extra INSTANCE = (GDI32Extra) Native.loadLibrary("gdi32", GDI32Extra.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean BitBlt(HDC hObject, int nXDest, int nYDest, int nWidth, int nHeight, HDC hObjectSource, int nXSrc, int nYSrc, DWORD dwRop);
    }

    public interface WinGDIExtra extends WinGDI {
        DWORD SRCCOPY = new DWORD(0x00CC0020);
    }
}
