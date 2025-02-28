package com.github.stkent.callingcard;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.SwitchCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.messages.Message;
import com.google.android.gms.nearby.messages.MessageListener;
import com.google.android.gms.nearby.messages.PublishCallback;
import com.google.android.gms.nearby.messages.PublishOptions;
import com.google.android.gms.nearby.messages.SubscribeCallback;
import com.google.android.gms.nearby.messages.SubscribeOptions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;

public final class NearbyActivity extends BaseActivity
        implements ConnectionCallbacks, OnConnectionFailedListener, OnCheckedChangeListener, UsersView.UserClickListener {

    private static final String TAG = "NearbyActivity";

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Uri.class, new UriTypeAdapter())
            .create();

    private static final String USER_DATA_EXTRA_KEY = "USER_DATA_EXTRA_KEY";
    private static final int PUBLISHING_ERROR_RESOLUTION_CODE = 5321;
    private static final int SUBSCRIBING_ERROR_RESOLUTION_CODE = 6546;

    protected static void launchWithUserData(
            @NonNull final User user,
            @NonNull final Context context) {

        final Intent intent = new Intent(context, NearbyActivity.class);
        intent.putExtra(USER_DATA_EXTRA_KEY, user);
        context.startActivity(intent);
    }

    private final PublishCallback publishCallback = new PublishCallback() {
        // Note: this seems to be invoked on a background thread!
        @Override
        public void onExpired() {
            /*
             * From https://developers.google.com/nearby/messages/android/pub-sub:
             *
             *   When actively publishing and subscribing, a "Nearby is in use" notification is
             *   presented, informing users that Nearby is active. This notification is only
             *   displayed when one or more apps are actively using Nearby, giving users a chance
             *   to conserve battery life if Nearby is not needed. It provides users with the
             *   following options:
             *
             *     - Navigate to an app to disable Nearby.
             *     - Force an app to stop using Nearby.
             *     - Navigate to the Nearby Settings screen.
             *
             *   You can use PublishCallback() [and SubscribeCallback()] to listen for cases when a
             *   user forces the app to stop using Nearby. When this happens, the onExpired()
             *   method is triggered.
             */
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelAllNearbyOperations();
                }
            });
        }
    };

    private final SubscribeCallback subscribeCallback = new SubscribeCallback() {
        // All comments in publishCallback apply here too.
        @Override
        public void onExpired() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cancelAllNearbyOperations();
                }
            });
        }
    };

    private final PublishOptions publishOptions
            = new PublishOptions.Builder().setCallback(publishCallback).build();

    private final SubscribeOptions subscribeOptions
            = new SubscribeOptions.Builder().setCallback(subscribeCallback).build();

    private final MessageListener messageListener = new MessageListener() {
        // Invoked once when a newly-published message is detected.
        @Override
        public void onFound(final Message message) {
            try {
                final User user = GSON.fromJson(new String(message.getContent()), User.class);

                if (!nearbyUsers.contains(user)) {
                    Log.d(TAG, "Discovered " + user.getName());

                    nearbyUsers.add(user);
                    refreshUsersViews();
                }
            } catch (final JsonSyntaxException e) {
                Log.e(TAG, "Invalid message received: " + new String(message.getContent()));
                Log.e(TAG, "Invalid message exception:", e);
            }
        }

        // Invoked once when previously-received message is lost.
        @Override
        public void onLost(final Message message) {
            try {
                final User user = GSON.fromJson(new String(message.getContent()), User.class);

                if (nearbyUsers.contains(user)) {
                    Log.d(TAG, "Lost " + user.getName());

                    nearbyUsers.remove(user);
                    refreshUsersViews();
                }
            } catch (final JsonSyntaxException e) {
                Log.e(TAG, "Invalid message reported as lost: " + new String(message.getContent()));
                Log.e(TAG, "Invalid message exception:", e);
            }
        }
    };

    private final List<User> nearbyUsers = new ArrayList<>();
    private final List<User> savedUsers = new ArrayList<>();

    @Bind(R.id.publishing_switch)
    protected SwitchCompat publishingSwitch;

    @Bind(R.id.published_user_view)
    protected UserView publishedUserView;

    @Bind(R.id.subscribing_switch)
    protected SwitchCompat subscribingSwitch;

    @Bind(R.id.nearby_users_view)
    protected UsersView nearbyUsersView;

    @Bind(R.id.saved_users_view)
    protected UsersView savedUsersView;

    private Message messageToPublish;
    private GoogleApiClient nearbyGoogleApiClient;
    private SavedUsersManager savedUsersManager;
    private boolean attemptingToPublish = false;
    private boolean attemptingToSubscribe = false;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby);
        ButterKnife.bind(this);

        publishingSwitch.setOnCheckedChangeListener(this);
        subscribingSwitch.setOnCheckedChangeListener(this);

        final User user = getIntent().getParcelableExtra(USER_DATA_EXTRA_KEY);
        publishedUserView.bindUser(user);
        publishedUserView.setPublishing(false);
        messageToPublish = new Message(GSON.toJson(user).getBytes());

        nearbyUsersView.setUserClickListener(this);
        savedUsersView.setUserClickListener(this);

        savedUsersManager = new SavedUsersManager(
                PreferenceManager.getDefaultSharedPreferences(this),
                GSON);

        nearbyGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Nearby.MESSAGES_API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        syncSwitchEnabledStatesWithGoogleApiClientState();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_sign_out:
                if (signInGoogleApiClient.isConnected()) {
                    Auth.GoogleSignInApi.signOut(signInGoogleApiClient).setResultCallback(
                            new ResultCallback<Status>() {
                                @Override
                                public void onResult(@NonNull final Status status) {
                                    cancelAllNearbyOperations();
                                    disconnectNearbyGoogleApiClient();

                                    NearbyActivity.this.startActivity(
                                            new Intent(NearbyActivity.this, SignInActivity.class));

                                    finish();
                                }
                            });
                } else if (signInGoogleApiClient.isConnecting()) {
                    toastSignOutFailedError();
                } else {
                    signInGoogleApiClient.connect();
                    toastSignOutFailedError();
                }

                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        savedUsers.clear();
        savedUsers.addAll(savedUsersManager.getSavedUsers());
        refreshUsersViews();

        if (!nearbyGoogleApiClient.isConnected() && !nearbyGoogleApiClient.isConnecting()) {
            nearbyGoogleApiClient.connect();
        }
    }

    @Override
    protected void onStop() {
        cancelAllNearbyOperations();
        disconnectNearbyGoogleApiClient();
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PUBLISHING_ERROR_RESOLUTION_CODE) {
            if (attemptingToPublish && resultCode == Activity.RESULT_OK) {
                // User was presented with the Nearby opt-in dialog and pressed "Allow".
                attemptToPublish();
            } else {
                // User declined to opt-in.
                publishingSwitch.setChecked(false);
            }

            attemptingToPublish = false;
        } else if (requestCode == SUBSCRIBING_ERROR_RESOLUTION_CODE) {
            if (attemptingToSubscribe && resultCode == Activity.RESULT_OK) {
                // User was presented with the Nearby opt-in dialog and pressed "Allow".
                attemptToSubscribe();
            } else {
                // User declined to opt-in.
                subscribingSwitch.setChecked(false);
            }

            attemptingToSubscribe = false;
        }
    }

    @Override
    public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.publishing_switch:
                if (publishingSwitch.isChecked()) {
                    if (nearbyGoogleApiClient.isConnected()) {
                        attemptToPublish();
                    } else if (!nearbyGoogleApiClient.isConnecting()) {
                        nearbyGoogleApiClient.connect();
                    }
                } else {
                    stopPublishing();
                    attemptingToPublish = false;
                }

                break;
            case R.id.subscribing_switch:
                if (subscribingSwitch.isChecked()) {
                    if (nearbyGoogleApiClient.isConnected()) {
                        attemptToSubscribe();
                    } else if (!nearbyGoogleApiClient.isConnecting()) {
                        nearbyGoogleApiClient.connect();
                    }
                } else {
                    stopSubscribing();
                    attemptingToSubscribe = false;
                }

                break;
            default:
                break;
        }
    }

    @Override
    public void onConnected(@Nullable final Bundle bundle) {
        syncSwitchEnabledStatesWithGoogleApiClientState();

        if (publishingSwitch.isChecked()) {
            attemptToPublish();
        }

        if (subscribingSwitch.isChecked()) {
            attemptToSubscribe();
        }
    }

    @Override
    public void onConnectionSuspended(final int i) {
        cancelAllNearbyOperations();
        // TODO: all usual error handling and resolution goes here
    }

    @Override
    public void onConnectionFailed(@NonNull final ConnectionResult connectionResult) {
        super.onConnectionFailed(connectionResult);
        cancelAllNearbyOperations();
        // TODO: all usual error handling and resolution goes here
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected void handleSignInResult(final GoogleSignInResult result) {
        super.handleSignInResult(result);

        if (!result.isSuccess()) {
            toastError("Sign-in required.");
            startActivity(new Intent(this, SignInActivity.class));
            finish();
        }
    }

    @Override
    public void onUserClick(@NonNull final User user) {
        if (savedUsers.contains(user)) {
            showDeleteUserDialog(user);
        } else {
            showSaveUserDialog(user);
        }
    }

    private void cancelAllNearbyOperations() {
        publishingSwitch.setChecked(false);
        subscribingSwitch.setChecked(false);

        nearbyUsers.clear();
        refreshUsersViews();
    }

    private void disconnectNearbyGoogleApiClient() {
        if (nearbyGoogleApiClient.isConnected() || nearbyGoogleApiClient.isConnecting()) {
            nearbyGoogleApiClient.disconnect();
        }

        syncSwitchEnabledStatesWithGoogleApiClientState();
    }

    private void attemptToPublish() {
        attemptingToPublish = true;

        Nearby.Messages.publish(nearbyGoogleApiClient, messageToPublish, publishOptions)
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(@NonNull final Status status) {
                        if (status.isSuccess() && publishingSwitch.isChecked()) {
                            publishedUserView.setPublishing(true);
                            attemptingToPublish = false;
                        } else if (status.hasResolution() && publishingSwitch.isChecked()) {
                            try {
                                status.startResolutionForResult(
                                        NearbyActivity.this, PUBLISHING_ERROR_RESOLUTION_CODE);

                            } catch (final IntentSender.SendIntentException e) {
                                publishedUserView.setPublishing(false);
                                attemptingToPublish = false;
                                toastError(status.getStatusMessage());
                            }
                        } else {
                            /*
                             * This branch will be hit if we cancel publishing before the initial
                             * async call to publish has completed (determined experimentally).
                             */
                            publishedUserView.setPublishing(false);
                            attemptingToPublish = false;
                            toastError(status.getStatusMessage());
                            // TODO: error-specific handling if desired
                        }
                    }
                });
    }

    private void stopPublishing() {
        // TODO: check PendingResult of this call and retry if it is not a success?
        Nearby.Messages.unpublish(nearbyGoogleApiClient, messageToPublish);
        publishedUserView.setPublishing(false);
    }

    private void attemptToSubscribe() {
        attemptingToSubscribe = true;

        Nearby.Messages.subscribe(nearbyGoogleApiClient, messageListener, subscribeOptions)
                .setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(@NonNull final Status status) {
                        if (status.isSuccess()) {
                            attemptingToSubscribe = false;
                        } else if (status.hasResolution() && subscribingSwitch.isChecked()) {
                            try {
                                status.startResolutionForResult(
                                        NearbyActivity.this, SUBSCRIBING_ERROR_RESOLUTION_CODE);

                            } catch (final IntentSender.SendIntentException e) {
                                attemptingToSubscribe = false;
                                toastError(status.getStatusMessage());
                            }
                        } else {
                            /*
                             * This branch will be hit if we cancel subscribing before the initial
                             * async call to subscribe has completed (determined experimentally).
                             */
                            attemptingToSubscribe = false;
                            toastError(status.getStatusMessage());
                            // TODO: error-specific handling if desired
                        }
                    }
                });
    }

    private void stopSubscribing() {
        // TODO: check PendingResult of this call and retry if it is not a success?
        Nearby.Messages.unsubscribe(nearbyGoogleApiClient, messageListener);
        nearbyUsers.clear();
        refreshUsersViews();
    }

    private void syncSwitchEnabledStatesWithGoogleApiClientState() {
        final boolean googleApiClientConnected = nearbyGoogleApiClient.isConnected();

        publishingSwitch.setEnabled(googleApiClientConnected);
        subscribingSwitch.setEnabled(googleApiClientConnected);
    }

    private AlertDialog.Builder getDefaultAlertBuilder() {
       return new AlertDialog.Builder(this)
                .setCancelable(false)
                .setNegativeButton("Cancel", null);
    }

    private void showSaveUserDialog(@NonNull final User user) {
        getDefaultAlertBuilder()
                .setMessage("Save " + user.getName() + "'s info?")
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        saveUser(user);
                    }
                })
                .show();
    }

    private void showDeleteUserDialog(@NonNull final User user) {
        getDefaultAlertBuilder()
                .setMessage("Delete " + user.getName() + "'s info?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        deleteSavedUser(user);
                    }
                })
                .show();
    }

    private void saveUser(@NonNull final User user) {
        if (!savedUsers.contains(user)) {
            savedUsers.add(user);
            refreshUsersViews();
            updatePersistedSavedUsers();
        }
    }

    private void deleteSavedUser(@NonNull final User user) {
        final boolean userWasDeleted = savedUsers.remove(user);

        if (userWasDeleted) {
            refreshUsersViews();
            updatePersistedSavedUsers();
        }
    }

    private void refreshUsersViews() {
        savedUsersView.setUsers(savedUsers);

        final List<User> usersToDisplay = new ArrayList<>(nearbyUsers);
        usersToDisplay.removeAll(savedUsers);
        nearbyUsersView.setUsers(usersToDisplay);
    }

    private void updatePersistedSavedUsers() {
        savedUsersManager.setUsers(savedUsers);
    }

    private void toastSignOutFailedError() {
        toastError("Sign out failed, please try again.");
    }

}
