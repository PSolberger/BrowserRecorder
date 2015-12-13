package recorder;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.util.ArrayList;

/**
 * Copyright (c) Nikolay Soloviev. All rights reserved.
 * @author Nikolay Soloviev <psolberger@gmail.com>
 */
public class InterfaceOperations {
    static final User32 user32 = User32.INSTANCE;

    public int getPIDByWindowContainsName(final String windowName) {
        final IntByReference pid = new IntByReference();

        user32.EnumWindows(new WinUser.WNDENUMPROC() {
            public boolean callback(WinDef.HWND hWnd, Pointer arg1) {
                if (!user32.IsWindowVisible(hWnd)) {
                    return true;
                }

                char[] windowTextС = new char[512];
                int windowTextLength = user32.GetWindowText(hWnd, windowTextС, 512);

                if (windowTextLength == 0) {
                    return true;
                }

                String сText = String.valueOf(windowTextС, 0, windowTextLength);

                if(!сText.contains(windowName)) {
                    return true;
                }

                IntByReference wPid = new IntByReference();
                user32.GetWindowThreadProcessId(hWnd, wPid);

                pid.setValue(wPid.getValue());

                return false;
            }
        }, null);

        return pid.getValue();
    }

    public static WinDef.HWND[] getHWNDsByPID(int pid) {
        final IntByReference localPid = new IntByReference(pid);
        final ArrayList<WinDef.HWND> hWnds = new ArrayList<WinDef.HWND>(0);

        user32.EnumWindows(new WinUser.WNDENUMPROC() {
            public boolean callback(WinDef.HWND hWnd, Pointer arg1) {
                IntByReference wPid = new IntByReference();
                user32.GetWindowThreadProcessId(hWnd, wPid);

                if(localPid.getValue() != wPid.getValue()) {
                    return true;
                }

                if (!user32.IsWindowVisible(hWnd)) {
                    return true;
                }

                if (user32.GetWindowTextLength(hWnd) == 0) {
                    return true;
                }

                hWnds.add(hWnd);

                return true;
            }
        }, null);

        return hWnds.toArray(new WinDef.HWND[hWnds.size()]);
    }
}
