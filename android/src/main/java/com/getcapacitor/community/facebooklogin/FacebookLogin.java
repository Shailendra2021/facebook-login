package com.getcapacitor.community.facebooklogin;

import android.content.Intent;
import android.os.Bundle;
import android.telephony.data.NetworkSliceInfo;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.FacebookRequestError;
import com.facebook.FacebookSdk;
import com.facebook.GraphRequest;
import com.facebook.GraphResponse;
import com.facebook.appevents.AppEventsLogger;
import com.facebook.login.LoginConfiguration;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(requestCodes = { FacebookLogin.FACEBOOK_SDK_REQUEST_CODE_OFFSET })
public class FacebookLogin extends Plugin {

    CallbackManager callbackManager;

    public static final int FACEBOOK_SDK_REQUEST_CODE_OFFSET = 0xface;
    private AppEventsLogger logger;
    private String latestCallbackId;

    @Override
    public void load() {
        Log.d(getLogTag(), "Entering load()");

        this.callbackManager = CallbackManager.Factory.create();
        this.logger = AppEventsLogger.newLogger(getContext());

        LoginManager.getInstance()
            .registerCallback(
                callbackManager,
                new FacebookCallback<LoginResult>() {
                    @Override
                    public void onSuccess(LoginResult loginResult) {
                        Log.d(getLogTag(), "LoginManager.onSuccess");

                        if (latestCallbackId == null) {
                            Log.e(getLogTag(), "LoginManager.onSuccess: no plugin saved call found.");
                        } else {
                            PluginCall savedCall = bridge.getSavedCall(latestCallbackId);

                            JSObject ret = new JSObject();
                            ret.put("accessToken", accessTokenToJson(loginResult.getAccessToken()));
                            ret.put("recentlyGrantedPermissions", collectionToJson(loginResult.getRecentlyGrantedPermissions()));
                            ret.put("recentlyDeniedPermissions", collectionToJson(loginResult.getRecentlyDeniedPermissions()));

                            savedCall.resolve(ret);

                            latestCallbackId = null;
                            bridge.releaseCall(savedCall);
                        }
                    }

                    @Override
                    public void onCancel() {
                        Log.d(getLogTag(), "LoginManager.onCancel");

                        if (latestCallbackId == null) {
                            Log.e(getLogTag(), "LoginManager.onCancel: no plugin saved call found.");
                        } else {
                            PluginCall savedCall = bridge.getSavedCall(latestCallbackId);

                            JSObject ret = new JSObject();
                            ret.put("accessToken", null);

                            savedCall.resolve(ret);

                            latestCallbackId = null;
                            bridge.releaseCall(savedCall);
                        }
                    }

                    @Override
                    public void onError(@NonNull FacebookException exception) {
                        Log.e(getLogTag(), "LoginManager.onError", exception);

                        if (latestCallbackId == null) {
                            Log.e(getLogTag(), "LoginManager.onError: no plugin saved call found.");
                        } else {
                            PluginCall savedCall = bridge.getSavedCall(latestCallbackId);
                            savedCall.reject(exception.toString());

                            latestCallbackId = null;
                            bridge.releaseCall(savedCall);
                        }
                    }
                }
            );
    }

    @PluginMethod
    public void initialize(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void login(PluginCall call) {
        Log.d(getLogTag(), "Entering login()");

        if (this.latestCallbackId != null) {
            Log.e(getLogTag(), "login: overlapped calls not supported");
            call.reject("Overlapped calls call not supported");
            return;
        }

        JSArray arg = call.getArray("permissions");
        Collection<String> permissions;
        try {
            permissions = arg.toList();
        } catch (Exception e) {
            Log.e(getLogTag(), "login: invalid 'permissions' argument", e);
            call.reject("Invalid permissions argument");
            return;
        }

        String nonce = call.getString("nonce", "");

        if (nonce.isEmpty()) {
            LoginConfiguration configuration = new LoginConfiguration(permissions);
            LoginManager.getInstance().logIn(this.getActivity(), configuration);
        } else {
            LoginConfiguration configuration = new LoginConfiguration(permissions, hashWithSha256(nonce));
            LoginManager.getInstance().logIn(this.getActivity(), configuration);
        }

        this.latestCallbackId = call.getCallbackId();
        bridge.saveCall(call);
    }

    @PluginMethod
    public void logout(PluginCall call) {
        Log.d(getLogTag(), "Entering logout()");

        LoginManager.getInstance().logOut();

        call.resolve();
    }

    @PluginMethod
    public void reauthorize(PluginCall call) {
        Log.d(getLogTag(), "Entering reauthorize()");

        if (this.latestCallbackId != null) {
            Log.e(getLogTag(), "reauthorize: overlapped calls not supported");

            call.reject("Overlapped calls call not supported");

            return;
        }

        LoginManager.getInstance().reauthorizeDataAccess(this.getActivity());

        this.latestCallbackId = call.getCallbackId();
        bridge.saveCall(call);
    }

    @PluginMethod
    public void getCurrentAccessToken(PluginCall call) {
        Log.d(getLogTag(), "Entering getCurrentAccessToken()");

        AccessToken accessToken = AccessToken.getCurrentAccessToken();

        JSObject ret = new JSObject();

        if (accessToken == null) {
            Log.d(getLogTag(), "getCurrentAccessToken: accessToken is null");
        } else {
            Log.d(getLogTag(), "getCurrentAccessToken: accessToken found");

            ret.put("accessToken", accessTokenToJson(accessToken));
        }

        call.resolve(ret);
    }

    @PluginMethod
    public void getProfile(final PluginCall call) {
        Log.d(getLogTag(), "Entering getProfile()");

        AccessToken accessToken = AccessToken.getCurrentAccessToken();

        if (accessToken == null) {
            Log.d(getLogTag(), "getProfile: accessToken is null");
            call.reject("You're not logged in. Call FacebookLogin.login() first to obtain an access token.");

            return;
        }

        if (accessToken.isExpired()) {
            Log.d(getLogTag(), "getProfile: accessToken is expired");
            call.reject("AccessToken is expired.");

            return;
        }

        Bundle parameters = new Bundle();

        try {
            JSArray fields = call.getArray("fields");
            String fieldsString = TextUtils.join(",", fields.toList());

            parameters.putString("fields", fieldsString);
        } catch (JSONException e) {
            call.reject("Can't handle fields", e);

            return;
        }

        GraphRequest graphRequest = GraphRequest.newMeRequest(
            accessToken,
            new GraphRequest.GraphJSONObjectCallback() {
                @Override
                public void onCompleted(JSONObject object, GraphResponse response) {
                    FacebookRequestError requestError = response.getError();

                    if (requestError != null) {
                        call.reject(requestError.getErrorMessage());

                        return;
                    }

                    try {
                        JSONObject jsonObject = response.getJSONObject();
                        JSObject jsObject = JSObject.fromJSONObject(jsonObject);

                        call.resolve(jsObject);
                    } catch (JSONException e) {
                        call.reject("Can't create response", e);
                    }
                }
            }
        );

        graphRequest.setParameters(parameters);
        graphRequest.executeAsync();
    }

    @PluginMethod
    public void logEvent(final PluginCall call) {
        Log.d(getLogTag(), "Entering logEvent()");
        String eventName = call.getString("eventName");
        if (eventName != null) {
            logger.logEvent(eventName);
        }
    }

    @PluginMethod
    public void setAutoLogAppEventsEnabled(final PluginCall call) {
        Log.d(getLogTag(), "Entering setAutoLogAppEventsEnabled()");
        Boolean enabled = call.getBoolean("enabled");
        if (enabled != null) {
            FacebookSdk.setAutoLogAppEventsEnabled(enabled);
        }
    }

    @PluginMethod
    public void setAdvertiserIDCollectionEnabled(final PluginCall call) {
        Log.d(getLogTag(), "Entering setAdvertiserIDCollectionEnabled()");
        Boolean enabled = call.getBoolean("enabled");
        if (enabled != null) {
            FacebookSdk.setAdvertiserIDCollectionEnabled(enabled);
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(getLogTag(), "Entering handleOnActivityResult(" + requestCode + ", " + resultCode + ")");

        if (callbackManager.onActivityResult(requestCode, resultCode, data)) {
            Log.d(getLogTag(), "onActivityResult succeeded");
        } else {
            Log.w(getLogTag(), "onActivityResult failed");
        }
    }

    /**
     * Convert date to ISO 8601 format.
     */
    private String dateToJson(Date date) {
        SimpleDateFormat simpleDateFormat;
        simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.ENGLISH);
        return simpleDateFormat.format(date);
    }

    private JSArray collectionToJson(Collection<String> list) {
        JSArray json = new JSArray();

        for (String item : list) {
            json.put(item);
        }

        return json;
    }

    private JSObject accessTokenToJson(AccessToken accessToken) {
        JSObject ret = new JSObject();
        ret.put("applicationId", accessToken.getApplicationId());
        ret.put("declinedPermissions", collectionToJson(accessToken.getDeclinedPermissions()));
        ret.put("expires", dateToJson(accessToken.getExpires()));
        ret.put("lastRefresh", dateToJson(accessToken.getLastRefresh()));
        ret.put("permissions", collectionToJson(accessToken.getPermissions()));
        ret.put("token", accessToken.getToken());
        ret.put("userId", accessToken.getUserId());
        ret.put("isExpired", accessToken.isExpired());

        return ret;
    }

    private static String hashWithSha256(String input) {
        try {
            // SHA-256 ハッシュを生成
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());

            // バイト配列を16進数文字列に変換
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0'); // 1桁の場合、先頭に0を追加
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256アルゴリズムが見つかりません", e);
        }
    }
}
