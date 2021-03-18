package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.termux.R;
import com.termux.app.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;
import com.termux.app.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.app.settings.properties.SharedProperties;
import com.termux.app.settings.properties.TermuxPropertyConstants;
import com.termux.app.utils.Logger;

/**
 * Third-party apps that are not part of termux world can run commands in termux context by either
 * sending an intent to RunCommandService or becoming a plugin host for the termux-tasker plugin
 * client.
 *
 * For the {@link RUN_COMMAND_SERVICE#ACTION_RUN_COMMAND} intent to work, there are 2 main requirements:
 *
 * 1. The `allow-external-apps` property must be set to "true" in ~/.termux/termux.properties in
 *      termux app, regardless of if the executable path is inside or outside the `~/.termux/tasker/`
 *      directory.
 * 2. The intent sender/third-party app must request the `com.termux.permission.RUN_COMMAND`
 *      permission in its `AndroidManifest.xml` and it should be granted by user to the app through the
 *      app's App Info permissions page in android settings, likely under Additional Permissions.
 *
 *
 *
 * The {@link RUN_COMMAND_SERVICE#ACTION_RUN_COMMAND} intent expects the following extras:
 *
 * 1. The {@code String} {@link RUN_COMMAND_SERVICE#EXTRA_COMMAND_PATH} extra for absolute path of
 *      command. This is mandatory.
 * 2. The {@code String[]} {@link RUN_COMMAND_SERVICE#EXTRA_ARGUMENTS} extra for any arguments to
 *      pass to command. This is optional.
 * 3. The {@code String} {@link RUN_COMMAND_SERVICE#EXTRA_WORKDIR} extra for current working directory
 *      of command. This is optional and defaults to {@link TermuxConstants#TERMUX_HOME_DIR_PATH}.
 * 4. The {@code boolean} {@link RUN_COMMAND_SERVICE#EXTRA_BACKGROUND} extra whether to run command
 *      in background or foreground terminal session. This is optional and defaults to {@code false}.
 * 5. The {@code String} {@link RUN_COMMAND_SERVICE#EXTRA_SESSION_ACTION} extra for for session action
 *      of foreground commands. This is optional and defaults to
 *      {@link TERMUX_SERVICE#VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY}.
 *
 *
 *
 * The {@link RUN_COMMAND_SERVICE#EXTRA_COMMAND_PATH} and {@link RUN_COMMAND_SERVICE#EXTRA_WORKDIR}
 * can optionally be prefixed with "$PREFIX/" or "~/" if an absolute path is not to be given.
 * The "$PREFIX/" will expand to {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH} and
 * "~/" will expand to {@link TermuxConstants#TERMUX_HOME_DIR_PATH}, followed by a forward slash "/".
 *
 *
 * To automatically bring termux session to foreground and start termux commands that were started
 * with background mode "false" in android >= 10 without user having to click the notification
 * manually requires termux to be granted draw over apps permission due to new restrictions
 * of starting activities from the background, this also applies to Termux:Tasker plugin.
 *
 * Check https://github.com/termux/termux-tasker for more details on allow-external-apps and draw
 * over apps and other limitations.
 *
 *
 * To reduce the chance of termux being killed by android even further due to violation of not
 * being able to call startForeground() within ~5s of service start in android >= 8, the user
 * may disable battery optimizations for termux.
 *
 *
 * If your third-party app is targeting sdk 30 (android 11), then it needs to add `com.termux`
 * package to the `queries` element or request `QUERY_ALL_PACKAGES` permission in its
 * `AndroidManifest.xml`. Otherwise it will get `PackageSetting{...... com.termux/......} BLOCKED`
 * errors in logcat and `RUN_COMMAND` won't work.
 * https://developer.android.com/training/basics/intents/package-visibility#package-name
 *
 *
 *
 * Sample code to run command "top" with java:
 *   Intent intent = new Intent();
 *   intent.setClassName("com.termux", "com.termux.app.RunCommandService");
 *   intent.setAction("com.termux.RUN_COMMAND");
 *   intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/top");
 *   intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-n", "5"});
 *   intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
 *   intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
 *   intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
 *   startService(intent);
 *
 * Sample code to run command "top" with "am startservice" command:
 * am startservice --user 0 -n com.termux/com.termux.app.RunCommandService \
 * -a com.termux.RUN_COMMAND \
 * --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/top' \
 * --esa com.termux.RUN_COMMAND_ARGUMENTS '-n,5' \
 * --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home' \
 * --ez com.termux.RUN_COMMAND_BACKGROUND 'false' \
 * --es com.termux.RUN_COMMAND_SESSION_ACTION '0'
 */
public class RunCommandService extends Service {

    private static final String NOTIFICATION_CHANNEL_ID = "termux_run_command_notification_channel";
    private static final int NOTIFICATION_ID = 1338;

    private static final String LOG_TAG = "RunCommandService";

    class LocalBinder extends Binder {
        public final RunCommandService service = RunCommandService.this;
    }

    private final IBinder mBinder = new RunCommandService.LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");
        runStartForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        // If invalid action passed, then just return
        if (!RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND.equals(intent.getAction())) {
            Logger.logError(LOG_TAG, "Invalid intent action to RunCommandService: \"" + intent.getAction() + "\"");
            return Service.START_NOT_STICKY;
        }

        // If allow-external-apps property is not set to "true"
        if (!SharedProperties.isPropertyValueTrue(this, TermuxPropertyConstants.getTermuxPropertiesFile(), TermuxConstants.PROP_ALLOW_EXTERNAL_APPS)) {
            Logger.logError(LOG_TAG, "RunCommandService requires allow-external-apps property to be set to \"true\" in \"" + TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH + "\" file");
            return Service.START_NOT_STICKY;
        }



        String commandPath = intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH);
        // If invalid commandPath passed, then just return
        if (commandPath == null || commandPath.isEmpty()) {
            Logger.logError(LOG_TAG, "Invalid coommand path to RunCommandService: \"" + commandPath + "\"");
            return Service.START_NOT_STICKY;
        }

        Uri programUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(getExpandedTermuxPath(commandPath)).build();



        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, programUri);
        execIntent.setClass(this, TermuxService.class);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, intent.getStringArrayExtra(RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS));
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, false));
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_SESSION_ACTION));

        String workingDirectory = intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_WORKDIR);
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, getExpandedTermuxPath(workingDirectory));
        }



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.startForegroundService(execIntent);
        } else {
            this.startService(execIntent);
        }

        runStopForeground();

        return Service.START_NOT_STICKY;
    }

    private void runStartForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private void runStopForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = new Notification.Builder(this);
        builder.setContentTitle(TermuxConstants.TERMUX_APP_NAME + " Run Command");
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Use a low priority:
        builder.setPriority(Notification.PRIORITY_LOW);

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Background color for small notification icon:
        builder.setColor(0xFF607D8B);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(NOTIFICATION_CHANNEL_ID);
        }

        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        String channelName = TermuxConstants.TERMUX_APP_NAME + " Run Command";
        int importance = NotificationManager.IMPORTANCE_LOW;

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    /** Replace "$PREFIX/" or "~/" prefix with termux absolute paths */
    public static String getExpandedTermuxPath(String path) {
        if(path != null && !path.isEmpty()) {
            path = path.replaceAll("^\\$PREFIX$", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            path = path.replaceAll("^\\$PREFIX/", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/");
            path = path.replaceAll("^~/$", TermuxConstants.TERMUX_HOME_DIR_PATH);
            path = path.replaceAll("^~/", TermuxConstants.TERMUX_HOME_DIR_PATH + "/");
        }

        return path;
    }
}
