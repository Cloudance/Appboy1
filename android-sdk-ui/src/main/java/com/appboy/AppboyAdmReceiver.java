package com.appboy;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;

import com.appboy.configuration.AppboyConfigurationProvider;
import com.appboy.push.AppboyNotificationActionUtils;
import com.appboy.push.AppboyNotificationUtils;
import com.appboy.support.AppboyLogger;

public final class AppboyAdmReceiver extends BroadcastReceiver {
  private static final String TAG = AppboyLogger.getAppboyLogTag(AppboyAdmReceiver.class);
  private static final String ADM_RECEIVE_INTENT_ACTION = "com.amazon.device.messaging.intent.RECEIVE";
  private static final String ADM_REGISTRATION_INTENT_ACTION = "com.amazon.device.messaging.intent.REGISTRATION";
  private static final String ADM_ERROR_KEY = "error";
  private static final String ADM_ERROR_DESCRIPTION_KEY = "error_description";
  private static final String ADM_REGISTRATION_ID_KEY = "registration_id";
  private static final String ADM_UNREGISTERED_KEY = "unregistered";
  private static final String ADM_MESSAGE_TYPE_KEY = "message_type";
  private static final String ADM_DELETED_MESSAGES_KEY = "deleted_messages";
  private static final String ADM_NUMBER_OF_MESSAGES_DELETED_KEY = "total_deleted";
  public static final String CAMPAIGN_ID_KEY = Constants.APPBOY_PUSH_CAMPAIGN_ID_KEY;

  @Override
  public void onReceive(Context context, Intent intent) {
    AppboyLogger.i(TAG, "Received broadcast message. Message: " + intent.toString());
    String action = intent.getAction();
    if (ADM_REGISTRATION_INTENT_ACTION.equals(action)) {
      handleRegistrationEventIfEnabled(new AppboyConfigurationProvider(context), context, intent);
    } else if (ADM_RECEIVE_INTENT_ACTION.equals(action)) {
      handleAppboyAdmReceiveIntent(context, intent);
    } else if (Constants.APPBOY_CANCEL_NOTIFICATION_ACTION.equals(action)) {
      AppboyNotificationUtils.handleCancelNotificationAction(context, intent);
    } else if (Constants.APPBOY_ACTION_CLICKED_ACTION.equals(action)) {
      AppboyNotificationActionUtils.handleNotificationActionClicked(context, intent);
    } else if (Constants.APPBOY_PUSH_CLICKED_ACTION.equals(action)) {
      AppboyNotificationUtils.handleNotificationOpened(context, intent);
    } else if (Constants.APPBOY_PUSH_DELETED_ACTION.equals(action)) {
      AppboyNotificationUtils.handleNotificationDeleted(context, intent);
    } else {
      AppboyLogger.w(TAG, "The ADM receiver received a message not sent from Appboy. Ignoring the message.");
    }
  }

  /**
   * Processes the registration/unregistration result returned from the ADM servers. If the
   * registration/unregistration is successful, this will store/clear the registration ID from the
   * device. Otherwise, it will log an error message and the device will not be able to receive ADM
   * messages.
   */
  boolean handleRegistrationIntent(Context context, Intent intent) {
    String error = intent.getStringExtra(ADM_ERROR_KEY);
    String errorDescription = intent.getStringExtra(ADM_ERROR_DESCRIPTION_KEY);
    String registrationId = intent.getStringExtra(ADM_REGISTRATION_ID_KEY);
    String unregistered = intent.getStringExtra(ADM_UNREGISTERED_KEY);

    if (error != null) {
      AppboyLogger.e(TAG, "Error during ADM registration: " + error + " description: " + errorDescription);
    } else if (registrationId != null) {
      AppboyLogger.i(TAG, "Registering for ADM messages with registrationId: " + registrationId);
      Appboy.getInstance(context).registerAppboyPushMessages(registrationId);
    } else if (unregistered != null) {
      AppboyLogger.w(TAG, "The device was un-registered from ADM: " + unregistered);
    } else {
      AppboyLogger.w(TAG, "The ADM registration intent is missing error information, registration id, and unregistration "
          + "confirmation. Ignoring.");
      return false;
    }
    return true;
  }

  /**
   * Handles both Braze data push ADM messages and notification messages. Notification messages are
   * posted to the notification center if the ADM message contains a title and body and the payload
   * is sent to the application via an Intent. Data push messages do not post to the notification
   * center, although the payload is forwarded to the application via an Intent as well.
   */
  boolean handleAppboyAdmMessage(Context context, Intent intent) {
    NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    String messageType = intent.getStringExtra(ADM_MESSAGE_TYPE_KEY);
    if (ADM_DELETED_MESSAGES_KEY.equals(messageType)) {
      int totalDeleted = intent.getIntExtra(ADM_NUMBER_OF_MESSAGES_DELETED_KEY, -1);
      if (totalDeleted == -1) {
        AppboyLogger.e(TAG, "Unable to parse ADM message. Intent: " + intent.toString());
      } else {
        AppboyLogger.i(TAG, "ADM deleted " + totalDeleted + " messages. Fetch them from Appboy.");
      }
      return false;
    } else {
      Bundle admExtras = intent.getExtras();
      AppboyLogger.d(TAG, "Push message payload received: " + admExtras);

      if (AppboyNotificationUtils.isUninstallTrackingPush(admExtras)) {
        // Note that this re-implementation of this method does not forward the notification to receivers.
        AppboyLogger.i(TAG, "Push message is uninstall tracking push. Doing nothing. Not forwarding this notification to broadcast receivers.");
        return false;
      }

      if (!admExtras.containsKey(Constants.APPBOY_PUSH_RECEIVED_TIMESTAMP_MILLIS)) {
        admExtras.putLong(Constants.APPBOY_PUSH_RECEIVED_TIMESTAMP_MILLIS, System.currentTimeMillis());
      }

      // Log the push delivery event
      AppboyNotificationUtils.logPushDeliveryEvent(context, admExtras);

      // Parsing the Braze data extras (data push).
      Bundle appboyExtras = AppboyNotificationUtils.getAppboyExtrasWithoutPreprocessing(admExtras);
      admExtras.putBundle(Constants.APPBOY_PUSH_EXTRAS_KEY, appboyExtras);

      if (AppboyNotificationUtils.isNotificationMessage(intent)) {
        int notificationId = AppboyNotificationUtils.getNotificationId(admExtras);
        admExtras.putInt(Constants.APPBOY_PUSH_NOTIFICATION_ID, notificationId);
        AppboyConfigurationProvider appConfigurationProvider = new AppboyConfigurationProvider(context);

        IAppboyNotificationFactory appboyNotificationFactory = AppboyNotificationUtils.getActiveNotificationFactory();
        Notification notification = appboyNotificationFactory.createNotification(appConfigurationProvider, context, admExtras, appboyExtras);

        if (notification == null) {
          AppboyLogger.d(TAG, "Notification created by notification factory was null. Not displaying notification.");
          return false;
        }

        notificationManager.notify(Constants.APPBOY_PUSH_NOTIFICATION_TAG, notificationId, notification);
        AppboyNotificationUtils.sendPushMessageReceivedBroadcast(context, admExtras);

        // Since we have received a notification, we want to wake the device screen.
        AppboyNotificationUtils.wakeScreenIfAppropriate(context, appConfigurationProvider, admExtras);

        // Set a custom duration for this notification.
        if (admExtras.containsKey(Constants.APPBOY_PUSH_NOTIFICATION_DURATION_KEY)) {
          int durationInMillis = Integer.parseInt(admExtras.getString(Constants.APPBOY_PUSH_NOTIFICATION_DURATION_KEY));
          AppboyNotificationUtils.setNotificationDurationAlarm(context, this.getClass(), notificationId, durationInMillis);
        }

        return true;
      } else {
        AppboyNotificationUtils.sendPushMessageReceivedBroadcast(context, admExtras);
        AppboyNotificationUtils.requestGeofenceRefreshIfAppropriate(context, admExtras);
        return false;
      }
    }
  }

  /**
   * Runs the handleAppboyAdmMessage method in a background thread in case of an image push
   * notification, which cannot be downloaded on the main thread.
   */
  public class HandleAppboyAdmMessageTask extends AsyncTask<Void, Void, Void> {
    private final Context mContext;
    private final Intent mIntent;

    public HandleAppboyAdmMessageTask(Context context, Intent intent) {
      mContext = context;
      mIntent = intent;
      execute();
    }

    @Override
    protected Void doInBackground(Void... voids) {
      try {
        handleAppboyAdmMessage(mContext, mIntent);
      } catch (Exception e) {
        AppboyLogger.e(TAG, "Failed to create and display notification.", e);
      }
      return null;
    }
  }

  void handleAppboyAdmReceiveIntent(Context context, Intent intent) {
    if (AppboyNotificationUtils.isAppboyPushMessage(intent)) {
      new HandleAppboyAdmMessageTask(context, intent);
    }
  }

  boolean handleRegistrationEventIfEnabled(AppboyConfigurationProvider appConfigurationProvider, Context context, Intent intent) {
    AppboyLogger.i(TAG, "Received ADM registration. Message: " + intent.toString());
    // Only handle ADM registration events if ADM registration handling is turned on in the
    // configuration file.
    if (appConfigurationProvider.isAdmMessagingRegistrationEnabled()) {
      AppboyLogger.d(TAG, "ADM enabled in appboy.xml. Continuing to process ADM registration intent.");
      handleRegistrationIntent(context, intent);
      return true;
    }
    AppboyLogger.w(TAG, "ADM not enabled in appboy.xml. Ignoring ADM registration intent. Note: you must set "
        + "com_appboy_push_adm_messaging_registration_enabled to true in your appboy.xml to enable ADM.");
    return false;
  }
}

