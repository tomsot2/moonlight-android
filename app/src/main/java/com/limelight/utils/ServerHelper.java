package com.limelight.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.limelight.AppView;
import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.ShortcutTrampoline;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        i.putExtra(Game.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }
    /**
     * Check if a display is a built-in/internal screen (not an externally connected display).
     * Uses flag-based heuristics and manufacturer name matching.
     */
    private static boolean isBuiltInDisplay(Display display) {
        int flags = display.getFlags();
        // FLAG_PRIVATE indicates the display is internal/private to the system
        if ((flags & Display.FLAG_PRIVATE) != 0) {
            return true;
        }
        // Internal screens often have device manufacturer in their name
        String displayName = display.getName();
        String deviceManufacturer = Build.MANUFACTURER;
        if (displayName != null && deviceManufacturer != null &&
            displayName.toLowerCase().contains(deviceManufacturer.toLowerCase())) {
            return true;
        }
        return false;
    }

    /**
     * Check if the device has two internal screens (dual-screen handheld like AYN Thor).
     */
    private static boolean isDualInternalScreenDevice(DisplayManager displayManager, Display defaultDisplay) {
        int internalScreenCount = 0;
        for (Display d : displayManager.getDisplays()) {
            LimeLog.info("Display " + d.getDisplayId() + ": " + d.getName() +
                         " " + d.getMode().getPhysicalWidth() + "x" + d.getMode().getPhysicalHeight() +
                         " flags=" + d.getFlags());
            if (isBuiltInDisplay(d)) {
                internalScreenCount++;
            }
        }
        LimeLog.info("Detected " + internalScreenCount + " internal screen(s)");
        return internalScreenCount >= 2;
    }

    /**
     * Return the display with the larger physical area from two candidates.
     */
    private static Display getLargerDisplay(Display a, Display b) {
        int areaA = a.getMode().getPhysicalWidth() * a.getMode().getPhysicalHeight();
        int areaB = b.getMode().getPhysicalWidth() * b.getMode().getPhysicalHeight();
        LimeLog.info("Comparing displays: " + a.getDisplayId() + " area=" + areaA +
                     " vs " + b.getDisplayId() + " area=" + areaB);
        return (areaA >= areaB) ? a : b;
    }

    /**
     * Picks which display gets the game stream and which gets the touch/mirror
     * controller, out of the default display and a secondary one. For two
     * internal displays (dual-screen device like AYN Thor) this defaults to the
     * physically larger one streaming — a proxy for "the right one" that can't
     * be verified against physical top/bottom position on unknown hardware
     * (e.g. identically-sized panels tie-break to the default display, which
     * isn't necessarily the top one). For a true external display, the
     * external one streams. Either way, swapDualScreens flips the result
     * unconditionally, so it corrects the assignment regardless of which case
     * the heuristic thinks it's in.
     *
     * @return a two-element array: {streamDisplay, controlDisplay}
     */
    private static Display[] getStreamAndControlDisplays(Display defaultDisplay, Display secondary, PreferenceConfiguration prefs) {
        Display streamDisplay;
        if (isBuiltInDisplay(defaultDisplay) && isBuiltInDisplay(secondary)) {
            LimeLog.info("Dual internal screen detected - selecting stream display");
            streamDisplay = getLargerDisplay(defaultDisplay, secondary);
        } else {
            // True external display (AR glasses, USB monitor, etc.): use it
            streamDisplay = secondary;
        }
        Display controlDisplay = (streamDisplay == defaultDisplay) ? secondary : defaultDisplay;

        if (prefs.swapDualScreens) {
            Display tmp = streamDisplay;
            streamDisplay = controlDisplay;
            controlDisplay = tmp;
        }
        return new Display[]{streamDisplay, controlDisplay};
    }

    public static Display getActiveDisplay(Context context, PreferenceConfiguration prefs) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        Display secondary = getSecondaryDisplay(context);

        if (secondary != null && prefs.enableFullExDisplay) {
            return getStreamAndControlDisplays(defaultDisplay, secondary, prefs)[0];
        }

        return defaultDisplay;
    }

    public static Display getSecondaryDisplay(Context context) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays();
        int mainDisplayId = Display.DEFAULT_DISPLAY;
        Display defaultDisplay = displayManager.getDisplay(mainDisplayId);

        // Collect non-default displays
        ArrayList<Display> nonDefaultDisplays = new ArrayList<>();
        for (Display d : displays) {
            LimeLog.info(d.toString());
            if (d.getDisplayId() != mainDisplayId) {
                nonDefaultDisplays.add(d);
            }
        }

        if (nonDefaultDisplays.isEmpty()) {
            return null;
        }

        // On dual-internal-screen devices, prefer the larger non-default internal display
        // as the "secondary" display for dual-screen mode
        if (isDualInternalScreenDevice(displayManager, defaultDisplay)) {
            // Return the largest non-default internal display
            Display best = null;
            int maxArea = 0;
            for (Display d : nonDefaultDisplays) {
                if (isBuiltInDisplay(d)) {
                    int area = d.getMode().getPhysicalWidth() * d.getMode().getPhysicalHeight();
                    if (area > maxArea) {
                        maxArea = area;
                        best = d;
                    }
                }
            }
            if (best != null) {
                LimeLog.info("Dual internal screen: selected secondary display " + best.getDisplayId());
                return best;
            }
        }

        // Prefer truly external displays over secondary internal screens
        for (Display d : nonDefaultDisplays) {
            if (!isBuiltInDisplay(d)) {
                LimeLog.info("External display detected: " + d.getDisplayId());
                return d;
            }
        }

        // Fallback: return the first non-default display
        return nonDefaultDisplays.get(0);
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder,
                                           boolean withVDisplay) {
        Intent gameIntent = null;
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(parent);
        DisplayManager displayManager = (DisplayManager) parent.getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        Display secondaryDisplay = getSecondaryDisplay(parent);
        boolean enableFullEx = prefConfig.enableFullExDisplay && secondaryDisplay != null;

        Display streamDisplay = null;
        Display controlDisplay = null;
        if (enableFullEx) {
            Display[] pair = getStreamAndControlDisplays(defaultDisplay, secondaryDisplay, prefConfig);
            streamDisplay = pair[0];
            controlDisplay = pair[1];
        }

        // Try to add secondary DisplayContext if supported and connected
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && enableFullEx) {
            Context displayContext = parent.createDisplayContext(streamDisplay);
            gameIntent = new Intent(displayContext, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if(gameIntent == null) gameIntent = new Intent(parent, Game.class);
        gameIntent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        gameIntent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        gameIntent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        gameIntent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay);
        gameIntent.putExtra(Game.EXTRA_SERVER_COMMANDS, (ArrayList<String>) computer.serverCommands);

        try {
            if (computer.serverCert != null) {
                gameIntent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }

        if (enableFullEx) {
            gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, streamDisplay.getDisplayId());
            Intent touchpadIntent = new Intent(parent, ExternalDisplayControlActivity.class);
            touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);
            // Signal to doStart() to launch touchpad on the control display
            touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_DISPLAY_ID, controlDisplay.getDisplayId());
            return touchpadIntent;
        }

        return gameIntent;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void doStart(
            Activity parent,
            NvApp app,
            ComputerDetails computer,
            ComputerManagerService.ComputerManagerBinder managerBinder,
            boolean withVDisplay
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = createStartIntent(parent, app, computer, managerBinder, withVDisplay);

        // For dual internal screen devices, launch the touchpad on the control display
        int launchDisplayId = intent.getIntExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_DISPLAY_ID, Display.DEFAULT_DISPLAY);
        if (launchDisplayId != Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(launchDisplayId);
            parent.startActivity(intent, options.toBundle());
        } else {
            parent.startActivity(intent);
        }
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_waiting),
                        parent.getResources().getString(R.string.nettest_text_waiting),
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_inconclusive);
                }
                else if (ret == 0) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_success);
                }
                else {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_failure);
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_done),
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final NvHTTP httpConn,
                              final String appName,
                              final Runnable onComplete,
                              final Runnable onFail
    ) {
        parent.runOnUiThread(() -> Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + appName + "...", Toast.LENGTH_SHORT).show());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean failed = false;
                try {
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + appName;
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + appName;
                    }
                } catch (HostHttpResponseException e) {
                    failed = true;
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    failed = true;
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (failed) {
                        if (onFail != null) {
                            onFail.run();
                        }
                    } else {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
            }
        }).start();

    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete
    ) {
        try {
            NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    managerBinder.getUniqueId(),
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(parent)
            );
            doQuit(
                    parent,
                    httpConn,
                    app.getAppName(),
                    onComplete,
                    null
            );
        } catch (Exception e) {
            e.printStackTrace();

            final String toastMessage = e.getMessage();
            parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
        }
    }
}
