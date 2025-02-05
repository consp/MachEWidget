package com.example.khughes.machewidget;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.icu.text.SimpleDateFormat;
import android.icu.util.TimeZone;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.example.khughes.machewidget.CarStatus.CarStatus;
import com.example.khughes.machewidget.OTAStatus.OTAStatus;
import com.example.khughes.machewidget.db.UserInfoDao;
import com.example.khughes.machewidget.db.UserInfoDatabase;
import com.example.khughes.machewidget.db.VehicleInfoDao;
import com.example.khughes.machewidget.db.VehicleInfoDatabase;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

public class NetworkCalls {
    public static final String COMMAND_SUCCESSFUL = "Command successful.";
    public static final String COMMAND_FAILED = "Command failed.";
    public static final String COMMAND_NO_NETWORK = "Network error.";
    public static final String COMMAND_EXCEPTION = "Exception occurred.";
    public static final String COMMAND_REMOTE_START_LIMIT = "Cannot extend remote start time without driving.";

    private static final int CMD_STATUS_SUCCESS = 200;
    private static final int CMD_STATUS_INPROGRESS = 552;
    private static final int CMD_STATUS_FAILED = 411;
    private static final int CMD_REMOTE_START_LIMIT = 590;

    public static void getAccessToken(Handler handler, Context context, String username, String password) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.getAccessToken(context, username, password);
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
//            if (intent.hasExtra("userId")) {
//                getUserVehicles(context, intent.getStringExtra("userId"));
//            }
        });
        t.start();
    }

    private static Intent getAccessToken(Context context, String username, String password) {
        Intent data = new Intent();
        String nextState = Constants.STATE_ATTEMPT_TO_GET_ACCESS_TOKEN;
        int stage = 1;
        UserInfoDao userDao = UserInfoDatabase.getInstance(context).userInfoDao();
        UserInfo userInfo = new UserInfo();
        String userId = null;
        String token = null;

        if (username == null) {
            LogFile.e(context, MainActivity.CHANNEL_ID, "NetworkCalls.getAccessToken() called with null username?");
        } else if (MainActivity.checkInternetConnection(context)) {
            AccessTokenService fordClient = NetworkServiceGenerators.createIBMCloudService(AccessTokenService.class, context);
            APIMPSService OAuth2Client = NetworkServiceGenerators.createAPIMPSService(APIMPSService.class, context);

            for (int retry = 2; retry >= 0; --retry) {
                try {

                    AccessToken accessToken = null;
                    Call<AccessToken> call;
                    Map<String, Object> jsonParams;
                    RequestBody body;

                    if (stage == 1) {
                        // Start by getting token we need for OAuth2 authentication
//                        call = fordClient.getAccessToken(Constants.CLIENTID, "password", username, password);
//                        Response<AccessToken> response = call.execute();
//                        if (!response.isSuccessful()) {
//                            continue;
//                        }
//                        accessToken = response.body();
//                        token = accessToken.getAccessToken();
                        token = Authenticate.newAuthenticate(context, username, password);
                        if (token == null) {
                            continue;
                        }
                        stage = 2;
                    }

                    // Next, try to get the actual token
                    if (stage == 2) {
                        jsonParams = new ArrayMap<>();
                        jsonParams.put("ciToken", token);
                        body = RequestBody.create((new JSONObject(jsonParams)).toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
                        call = OAuth2Client.getAccessToken(body);
                        Response<AccessToken> response = call.execute();
                        if (!response.isSuccessful()) {
                            continue;
                        }

                        nextState = Constants.STATE_HAVE_TOKEN;
                        accessToken = response.body();
                        token = accessToken.getAccessToken();
                        userInfo.setAccessToken(token);
                        userInfo.setRefreshToken(accessToken.getRefreshToken());
                        LocalDateTime time = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(accessToken.getExpiresIn());
                        long nextTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                        userInfo.setExpiresIn(nextTime);
                        stage = 3;
                    }

                    // Next, try to get the user's data
                    if (stage == 3) {
                        call = OAuth2Client.getUserProfile(token);
                        Response<AccessToken> response = call.execute();
                        if (!response.isSuccessful()) {
                            continue;
                        }

                        accessToken = response.body();
                        AccessToken.UserProfile userProfile = accessToken.getUserProfile();
                        userId = UUID.nameUUIDFromBytes(userProfile.getUserGuid().getBytes()).toString();
                        userDao.deleteUserInfoByUserId(userId);
                        userInfo.setUserId(userId);

                        userInfo.setLanguage(userProfile.getLanguage());
                        userInfo.setCountry(userProfile.getCountry());
                        userInfo.setUomPressure(userProfile.getUomPressure());
                        userInfo.setUomDistance(userProfile.getUomDistance());
                        userInfo.setUomSpeed(userProfile.getUomSpeed());
                        userInfo.setProgramState(nextState);

                        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
                        boolean savingCredentials = sharedPref.getBoolean(context.getResources().getString(R.string.save_credentials_key), true);
                        if (savingCredentials) {
                            Encryption encrypt = new Encryption(context);
                            String encryptedUsername = encrypt.getCryptoString(username);
                            String encryptedPassword = encrypt.getCryptoString(password);
                            userInfo.setUsername(encryptedUsername);
                            userInfo.setPassword(encryptedPassword);
                        }

                        userDao.insertUserInfo(userInfo);

                        data.putExtra("access_token", token);
                        data.putExtra("language", userInfo.getLanguage());
                        data.putExtra("country", userInfo.getCountry());
                        data.putExtra("userId", userId);
                        break;
                    }
                } catch (java.net.SocketTimeoutException ee) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.SocketTimeoutException in NetworkCalls.getAccessToken");
                    LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
                    try {
                        Thread.sleep(3 * 1000);
                    } catch (InterruptedException e) {
                    }
                } catch (java.net.UnknownHostException e3) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.UnknownHostException in NetworkCalls.getAccessToken");
                    break;
                } catch (Exception e) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getAccessToken: ", e);
                    break;
                }
            }
        }

        userDao.updateProgramState(nextState, userId);
        data.putExtra("action", nextState);
        return data;
    }

    public static void refreshAccessToken(Handler handler, Context context, String userId, String refreshToken) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.refreshAccessToken(context, userId, refreshToken);
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    private static Intent refreshAccessToken(Context context, String userId, String refreshToken) {
        Intent data = new Intent();
        String nextState = Constants.STATE_ATTEMPT_TO_REFRESH_ACCESS_TOKEN;
        UserInfoDao dao = UserInfoDatabase.getInstance(context)
                .userInfoDao();

        if (MainActivity.checkInternetConnection(context)) {
            Map<String, String> jsonParams = new ArrayMap<>();
            jsonParams.put("refresh_token", refreshToken);
            RequestBody body = RequestBody.create((new JSONObject(jsonParams)).toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
            APIMPSService OAuth2Client = NetworkServiceGenerators.createAPIMPSService(APIMPSService.class, context);
            for (int retry = 2; retry >= 0; --retry) {
                Call<AccessToken> call = OAuth2Client.refreshAccessToken(body);
                try {
                    Response<AccessToken> response = call.execute();
                    LogFile.i(context, MainActivity.CHANNEL_ID, "refresh here.");
                    if (response.isSuccessful()) {
                        LogFile.i(context, MainActivity.CHANNEL_ID, "refresh successful");
                        AccessToken accessToken = response.body();
                        UserInfo userInfo = dao.findUserInfo(userId);
                        String token = accessToken.getAccessToken();
                        userInfo.setAccessToken(token);
                        userInfo.setRefreshToken(accessToken.getRefreshToken());
                        LocalDateTime time = LocalDateTime.now(ZoneId.systemDefault()).plusSeconds(accessToken.getExpiresIn());
                        long nextTime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                        userInfo.setExpiresIn(nextTime);
                        dao.updateUserInfo(userInfo);
                        data.putExtra("access_token", token);
                        data.putExtra("language", userInfo.getLanguage());
                        data.putExtra("country", userInfo.getCountry());
                        data.putExtra("userId", userId);
                        nextState = Constants.STATE_HAVE_TOKEN_AND_VIN;
                    } else {
                        LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                        LogFile.i(context, MainActivity.CHANNEL_ID, "refresh unsuccessful, attempting to authorize");
                        nextState = Constants.STATE_ATTEMPT_TO_GET_ACCESS_TOKEN;
                    }
                    break;
                } catch (java.net.SocketTimeoutException ee) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.SocketTimeoutException in NetworkCalls.refreshAccessToken");
                    LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
                    try {
                        Thread.sleep(3 * 1000);
                    } catch (InterruptedException e) {
                    }
                } catch (java.net.UnknownHostException e3) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.UnknownHostException in NetworkCalls.refreshAccessToken");
                    break;
                } catch (Exception e) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.refreshAccessToken: ", e);
                    break;
                }
            }
        }
        dao.updateProgramState(nextState, userId);
        data.putExtra("action", nextState);
        return data;
    }

    public static void getUserVehicles(Handler handler, Context context, String userId) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.getUserVehicles(context, userId);
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    private static Intent getUserVehicles(Context context, String userId) {
        Intent data = new Intent();
        String nextState = Constants.STATE_HAVE_TOKEN;

        UserInfoDao userDao = UserInfoDatabase.getInstance(context).userInfoDao();
        UserInfo userInfo = userDao.findUserInfo(userId);
        if (userInfo == null) {
            LogFile.e(context, MainActivity.CHANNEL_ID, "NetworkCalls.getUserVehicles(): userInfo is null for userId " + userId);
            data.putExtra("action", nextState);
            return data;
        }

        String token = userInfo.getAccessToken();
        String country = userInfo.getCountry();

        if (MainActivity.checkInternetConnection(context)) {
            APIMPSService userDetailsClient = NetworkServiceGenerators.createAPIMPSService(APIMPSService.class, context);

            for (int retry = 2; retry >= 0; --retry) {
                try {
                    Map<String, String> modified = new ArrayMap<>();
                    modified.put("If-Modified-Since", userInfo.getLastModified());
                    Map<String, Object> entityRefresh = new ArrayMap<>();
                    entityRefresh.put("userVehicles", modified);
                    Map<String, Object> jsonParams = new ArrayMap<>();
                    jsonParams.put("dashboardRefreshRequest", "EntityRefresh");
                    jsonParams.put("entityRefresh", entityRefresh);
                    RequestBody body = RequestBody.create((new JSONObject(jsonParams)).toString(), okhttp3.MediaType.parse("application/json; charset=utf-8"));
                    Call<UserDetails> call = userDetailsClient.getUserDetails(token, Constants.APID, country, body);
                    Response<UserDetails> response = call.execute();
                    if (response.isSuccessful()) {
                        LogFile.i(context, MainActivity.CHANNEL_ID, "getVehicles successful.");
                        UserDetails userDetails = response.body();
                        String lastModified = userDetails.getUserVehicles().getStatus().getLastModified();
                        userDao.updateLastModified(lastModified, userId);
                        Map<String, String> vehicleInfo = new HashMap<>();
                        for (UserDetails.VehicleDetail vehicle : userDetails.getUserVehicles().getVehicleDetails()) {
                            String VIN = vehicle.getVin();
                            vehicleInfo.put(VIN, vehicle.getNickName());
                        }
                        LogFile.i(context, MainActivity.CHANNEL_ID, "getVehicles " + vehicleInfo);
                        if (!vehicleInfo.isEmpty()) {
                            ProfileManager.updateProfile(context, userInfo, vehicleInfo);
                        }
                        nextState = Constants.STATE_HAVE_TOKEN_AND_VIN;
                        break;
                    } else {
                        LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                        LogFile.i(context, MainActivity.CHANNEL_ID, "getVehicles UNSUCCESSFUL.");
                        // For either of these client errors, we probably need to refresh the access token
                        if (response.code() == Constants.HTTP_BAD_REQUEST || response.code() == Constants.HTTP_UNAUTHORIZED) {
                            nextState = Constants.STATE_ATTEMPT_TO_REFRESH_ACCESS_TOKEN;
                        }
                    }
                    LogFile.i(context, MainActivity.CHANNEL_ID, "getVehicles UNSUCCESSFUL.");
                    LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
                } catch (java.net.SocketTimeoutException ee) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.SocketTimeoutException in NetworkCalls.getUserVehicles");
                    LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
                    try {
                        Thread.sleep(3 * 1000);
                    } catch (InterruptedException e) {
                    }
                } catch (java.net.UnknownHostException e3) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.UnknownHostException in NetworkCalls.getUserVehicles");
                    break;
                } catch (Exception e) {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getUserVehicles: ", e);
                    break;
                }
            }
        }
        userDao.updateProgramState(nextState, userId);
        data.putExtra("action", nextState);
        return data;
    }

    public static void getStatus(Handler handler, Context context, String userId) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.getStatus(context, userId);
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    private static Intent getStatus(Context context, String userId) {
        UserInfoDao userDao = UserInfoDatabase.getInstance(context)
                .userInfoDao();
        VehicleInfoDao infoDao = VehicleInfoDatabase.getInstance(context)
                .vehicleInfoDao();

        UserInfo userInfo = userDao.findUserInfo(userId);
        final String token = userInfo.getAccessToken();
        final String language = userInfo.getLanguage();
        final String country = userInfo.getCountry();

        Intent data = new Intent();
        String nextState = Constants.STATE_ATTEMPT_TO_REFRESH_ACCESS_TOKEN;

        LogFile.d(context, MainActivity.CHANNEL_ID, "userId = " + userId);
        LogFile.d(context, MainActivity.CHANNEL_ID, "getting status for " + infoDao.findVehicleInfoByUserId(userId).size() + " vehicles");

        for (VehicleInfo info : infoDao.findVehicleInfoByUserId(userId)) {
            String VIN = info.getVIN();
            if (!info.isEnabled()) {
                LogFile.i(context, MainActivity.CHANNEL_ID, VIN + " is disabled: skipping");
                continue;
            } else {
                LogFile.i(context, MainActivity.CHANNEL_ID, "getting status for VIN " + VIN);
            }

            boolean forceUpdate = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getResources().getString(R.string.forceUpdate_key), false);

            if (forceUpdate) {
                LocalDateTime time = LocalDateTime.now(ZoneId.systemDefault());
                long nowtime = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long lasttime = info.getLastRefreshTime();

                LogFile.d(context, MainActivity.CHANNEL_ID, "last refresh was " + (nowtime - lasttime) / (1000 * 60) + " min ago");
                LogFile.d(context, MainActivity.CHANNEL_ID, "last refresh was " + (nowtime - lasttime) / (1000 * 60) + " min ago");

                if ((nowtime - lasttime) / (1000 * 60) > 6 * 60 && info.getCarStatus() != null && !info.getCarStatus().getDeepSleep() && info.getCarStatus().getLVBVoltage() > 12) {
                    updateStatus(context, info.getVIN());
                }
            }

            boolean statusUpdated = false;
            boolean supportsOTA = info.isSupportsOTA();
            if (MainActivity.checkInternetConnection(context)) {
                USAPICVService statusClient = NetworkServiceGenerators.createUSAPICVService(USAPICVService.class, context);
                DigitalServicesService OTAstatusClient = NetworkServiceGenerators.createDIGITALSERVICESService(DigitalServicesService.class, context);
                for (int retry = 2; retry >= 0; --retry) {

                    try {
                        // Try to get the latest car status
                        Call<CarStatus> callStatus = statusClient.getStatus(token, language, Constants.APID, VIN);
                        Response<CarStatus> responseStatus = callStatus.execute();
                        if (responseStatus.isSuccessful()) {
                            LogFile.i(context, MainActivity.CHANNEL_ID, "status successful.");
                            CarStatus car = responseStatus.body();
                            if (car.getStatus() == Constants.HTTP_SERVER_ERROR) {
                                LogFile.i(context, MainActivity.CHANNEL_ID, "server is broken");
                            } else if (car.getVehiclestatus() != null) {
                                Calendar lastRefreshTime = Calendar.getInstance();
                                SimpleDateFormat sdf = new SimpleDateFormat(Constants.STATUSTIMEFORMAT, Locale.US);
                                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                                long currentRefreshTime = 0;
                                try {
                                    lastRefreshTime.setTime(sdf.parse(car.getLastRefresh()));
                                    currentRefreshTime = lastRefreshTime.toInstant().toEpochMilli();
                                } catch (ParseException e) {
                                    LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getStatus: ", e);
                                }

                                // If the charging status changes, reset the old charge station info so we know to update it later
                                long priorRefreshTime = info.getLastRefreshTime();
                                if (priorRefreshTime <= currentRefreshTime) {
                                    info.setCarStatus(car);
                                    info.setLastUpdateTime();
                                    info.setLastRefreshTime(currentRefreshTime);
                                    statusUpdated = true;
                                }
                                Notifications.checkLVBStatus(context, car, info);
                                Notifications.checkTPMSStatus(context, car, info);
                                LogFile.i(context, MainActivity.CHANNEL_ID, "got status");
                                nextState = Constants.STATE_HAVE_TOKEN_AND_VIN;
                            } else {
                                nextState = Constants.STATE_HAVE_TOKEN;
                                LogFile.i(context, MainActivity.CHANNEL_ID, "vehicle status is null");
                            }
                        } else {
                            LogFile.i(context, MainActivity.CHANNEL_ID, responseStatus.raw().toString());
                            LogFile.i(context, MainActivity.CHANNEL_ID, "status UNSUCCESSFUL.");
                            // For either of these client errors, we probably need to refresh the access token
                            if (responseStatus.code() == Constants.HTTP_BAD_REQUEST) {
//                            if (responseStatus.code() == Constants.HTTP_BAD_REQUEST || responseStatus.code() == Constants.HTTP_UNAUTHORIZED) {
                                nextState = Constants.STATE_ATTEMPT_TO_REFRESH_ACCESS_TOKEN;
                            }
                        }

                        // Don't bother checking the OTA status if we've seen the vehicle doesn't support them
                        if (supportsOTA) {
                            // Try to get the OTA update status
                            Call<OTAStatus> callOTA = OTAstatusClient.getOTAStatus(token, language, Constants.APID, country, VIN);
                            Response<OTAStatus> responseOTA = callOTA.execute();
                            if (responseStatus.isSuccessful()) {
                                LogFile.i(context, MainActivity.CHANNEL_ID, "OTA status successful.");
                                OTAStatus status = responseOTA.body();

                                // Check to see if it looks like the vehicle support OTA updates
                                if (!Utils.OTASupportCheck(status.getOtaAlertStatus())) {
                                    LogFile.i(context, MainActivity.CHANNEL_ID, "This vehicle doesn't support OTA updates.");
                                    info.setSupportsOTA(false);
                                }
                                // Only save the status if there is something in the fuseResponse
                                else if (status.getOtaAlertStatus() != null && status.getFuseResponseList() != null) {
                                    info.fromOTAStatus(status);
                                }
                                statusUpdated = true;
                            } else {
                                try {
                                    if (responseStatus.errorBody().string().contains("UpstreamException")) {
                                        OTAStatus status = new OTAStatus();
                                        status.setError("UpstreamException");
                                    }
                                } catch (Exception e) {
                                    LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getStatus: ", e);
                                }
                                LogFile.i(context, MainActivity.CHANNEL_ID, responseStatus.raw().toString());
                                LogFile.i(context, MainActivity.CHANNEL_ID, "OTA UNSUCCESSFUL.");
                            }
                        } else {
                            LogFile.i(context, MainActivity.CHANNEL_ID, "OTA not supported: skipping check");
                        }

                        // If the vehicle info changed, commit
                        if (statusUpdated) {
                            infoDao.updateVehicleInfo(info);
                        }

//                        // get vehicle color information
//                        Call<VehicleInfo> thing = statusClient.getVehicleInfo(token, Constants.APID, VIN);
//                        Response<VehicleInfo> othething = thing.execute();

                        break;

                    } catch (java.net.SocketTimeoutException e2) {
                        LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.SocketTimeoutException in NetworkCalls.getStatus");
                        LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
                        try {
                            Thread.sleep(3 * 1000);
                        } catch (InterruptedException e) {
                        }
                    } catch (java.net.UnknownHostException e3) {
                        LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.UnknownHostException in NetworkCalls.getStatus");
                        // If the vehicle info changed, commit
                        if (statusUpdated) {
                            infoDao.updateVehicleInfo(info);
                        }
                        break;
                    } catch (Exception e) {
                        LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getStatus: ", e);
                        // If the vehicle info changed, commit
                        if (statusUpdated) {
                            infoDao.updateVehicleInfo(info);
                        }
                        break;
                    }
                }
            }
        }

        userDao.updateProgramState(nextState, userId);
        data.putExtra("action", nextState);
        return data;
    }

//    public static void getChargeStation(Handler handler, Context context, String token) {
//        Thread t = new Thread(() -> {
//            NetworkCalls.getChargeStation(context, token);
//            Message m = Message.obtain();
//            handler.sendMessage(m);
//        });
//        t.start();
//    }
//
//    private static void getChargeStation(Context context, String token) {
//        String VIN = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.VIN_key), "");
//
//        if (MainActivity.checkInternetConnection(context)) {
//            APIMPSService chargeStationClient = NetworkServiceGenerators.createAPIMPSService(APIMPSService.class, context);
//            for (int retry = 2; retry >= 0; --retry) {
//                Call<ChargeStation> call = chargeStationClient.getChargeStation(token, Constants.APID, VIN);
//                try {
//                    Response<ChargeStation> response = call.execute();
//                    if (response.isSuccessful()) {
//                        LogFile.i(context, MainActivity.CHANNEL_ID, "charge station successful.");
////                        appInfo.setChargeStation(VIN, response.body());
//                    } else {
//                        try {
//                            if (response.errorBody().string().contains("UpstreamException")) {
//                                OTAStatus status = new OTAStatus();
//                                status.setError("UpstreamException");
//                            }
//                        } catch (Exception e) {
//                            LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getChargeStation: ", e);
//                        }
//                        LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
//                        LogFile.i(context, MainActivity.CHANNEL_ID, "charge station UNSUCCESSFUL.");
//                    }
//                    break;
//                } catch (java.net.SocketTimeoutException ee) {
//                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.SocketTimeoutException in NetworkCalls.getChargeStation");
//                    LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
//                    try {
//                        Thread.sleep(3 * 1000);
//                    } catch (InterruptedException e) {
//                    }
//                } catch (java.net.UnknownHostException e3) {
//                    LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.UnknownHostException in NetworkCalls.getChargeStation");
//                    break;
//                } catch (Exception e) {
//                    LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getChargeStation: ", e);
//                    break;
//                }
//            }
//        }
//    }

    public static void getVehicleImage(Context context, String token, String VIN, String country) {
        // Create the images folder if necessary
        File imageDir = new File(context.getDataDir(), Constants.IMAGES_FOLDER);
        if (!imageDir.exists()) {
            imageDir.mkdir();
        }
        String modelYear = String.valueOf(Utils.getModelYear(VIN));
        DigitalServicesService vehicleImageClient = NetworkServiceGenerators.createDIGITALSERVICESService(DigitalServicesService.class, context);

        if (MainActivity.checkInternetConnection(context)) {
            for (int angle = 1; angle <= 5; ++angle) {
                final File image = new File(imageDir, VIN + "_angle" + angle + ".png");
                if (!image.exists()) {
                    final int a = angle;
                    Thread t = new Thread(() -> {
                        for (int retry = 2; retry >= 0; --retry) {
                            Call<ResponseBody> call = vehicleImageClient.getVehicleImage(token, Constants.APID, VIN, modelYear, country, String.valueOf(a));
                            try {
                                Response<ResponseBody> response = call.execute();
                                if (response.isSuccessful()) {
                                    Files.copy(response.body().byteStream(), image.toPath());
                                    LogFile.i(context, MainActivity.CHANNEL_ID, "vehicle image " + a + " successful.");
                                    MainActivity.updateWidget(context);
                                } else {
                                    LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                                    if (response.code() == Constants.HTTP_BAD_REQUEST) {
                                        LogFile.i(context, MainActivity.CHANNEL_ID, "vehicle image " + a + " UNSUCCESSFUL.");
                                    }
                                }
                                break;
                            } catch (java.net.SocketTimeoutException ee) {
                                LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.SocketTimeoutException in NetworkCalls.getVehicleImage");
                                LogFile.e(context, MainActivity.CHANNEL_ID, MessageFormat.format("    {0} retries remaining", retry));
                                try {
                                    Thread.sleep(3 * 1000);
                                } catch (InterruptedException e) {
                                }
                            } catch (java.net.UnknownHostException e3) {
                                LogFile.e(context, MainActivity.CHANNEL_ID, "java.net.UnknownHostException in NetworkCalls.getVehicleImage");
                                break;
                            } catch (Exception e) {
                                LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.getVehicleImage: ", e);
                                break;
                            }
                        }
                    });
                    t.start();
                }
            }
        }
    }

    public static void remoteStart(Handler handler, Context context, String VIN) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.execCommand(context, VIN, "v5", "engine", "start", "put");
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    public static void remoteStop(Handler handler, Context context, String VIN) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.execCommand(context, VIN, "v5", "engine", "start", "delete");
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    public static void lockDoors(Handler handler, Context context, String VIN) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.execCommand(context, VIN, "v2", "doors", "lock", "put");
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    public static void unlockDoors(Handler handler, Context context, String VIN) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.execCommand(context, VIN, "v2", "doors", "lock", "delete");
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    private static Intent execCommand(Context context, String VIN, String version, String component, String operation, String request) {
        VehicleInfo vehInfo = VehicleInfoDatabase.getInstance(context)
                .vehicleInfoDao().findVehicleInfoByVIN(VIN);
        UserInfo userInfo = UserInfoDatabase.getInstance(context)
                .userInfoDao().findUserInfo(vehInfo.getUserId());
        String token = userInfo.getAccessToken();
        Intent data = new Intent();

        if (!MainActivity.checkInternetConnection(context)) {
            data.putExtra("action", COMMAND_NO_NETWORK);
        } else {
            USAPICVService commandServiceClient = NetworkServiceGenerators.createUSAPICVService(USAPICVService.class, context);
            Call<CommandStatus> call;
            if (request.equals("put")) {
                call = commandServiceClient.putCommand(token, Constants.APID,
                        version, VIN, component, operation);
            } else {
                call = commandServiceClient.deleteCommand(token, Constants.APID,
                        version, VIN, component, operation);
            }
            try {
                Response<CommandStatus> response = call.execute();
                if (response.isSuccessful()) {
                    CommandStatus status = response.body();
                    if (status.getStatus() == CMD_STATUS_SUCCESS) {
                        LogFile.i(context, MainActivity.CHANNEL_ID, "CMD send successful.");
                        Looper.prepare();
                        Toast.makeText(context, "Command transmitted.", Toast.LENGTH_SHORT).show();
                        data.putExtra("action", execResponse(context, token, VIN, component, operation, status.getCommandId()));
                    } else if (status.getStatus() == CMD_REMOTE_START_LIMIT) {
                        LogFile.i(context, MainActivity.CHANNEL_ID, "CMD send UNSUCCESSFUL.");
                        data.putExtra("action", COMMAND_REMOTE_START_LIMIT);
                    } else {
                        data.putExtra("action", COMMAND_EXCEPTION);
                        LogFile.i(context, MainActivity.CHANNEL_ID, "CMD send unknown response.");
                        LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                    }
                } else {
                    data.putExtra("action", COMMAND_FAILED);
                    LogFile.i(context, MainActivity.CHANNEL_ID, "CMD send UNSUCCESSFUL.");
                    LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                }
            } catch (Exception e) {
                data.putExtra("action", COMMAND_EXCEPTION);
                LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.execCommand: ", e);
            }
        }
        return data;
    }

    private static String execResponse(Context context, String token, String VIN, String component, String operation, String idCode) {
        // Delay 5 seconds before starting to check on status
        try {
            Thread.sleep(5 * 1000);
        } catch (InterruptedException e) {
            LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.execResponse: ", e);
        }

        USAPICVService commandServiceClient = NetworkServiceGenerators.createUSAPICVService(USAPICVService.class, context);
        try {
            for (int retries = 0; retries < 10; ++retries) {
                Call<CommandStatus> call = commandServiceClient.getCommandResponse(token,
                        Constants.APID, VIN, component, operation, idCode);
                Response<CommandStatus> response = call.execute();
                if (response.isSuccessful()) {
                    CommandStatus status = response.body();
                    switch (status.getStatus()) {
                        case CMD_STATUS_SUCCESS:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "CMD response successful.");
                            return COMMAND_SUCCESSFUL;
                        case CMD_STATUS_FAILED:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "CMD response failed.");
                            return COMMAND_FAILED;
                        case CMD_STATUS_INPROGRESS:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "CMD response waiting.");
                            break;
                        default:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "CMD response unknown: status = " + status.getStatus());
                            return COMMAND_FAILED;
                    }
                } else {
                    LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                    LogFile.i(context, MainActivity.CHANNEL_ID, "CMD response UNSUCCESSFUL.");
                    return COMMAND_FAILED;
                }
                Thread.sleep(2 * 1000);
            }
            LogFile.i(context, MainActivity.CHANNEL_ID, "CMD timeout?");
            return COMMAND_FAILED;
        } catch (Exception e) {
            LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.execResponse: ", e);
            return COMMAND_EXCEPTION;
        }
    }

    public static void updateStatus(Handler handler, Context context, String VIN) {
        Thread t = new Thread(() -> {
            Intent intent = NetworkCalls.updateStatus(context, VIN);
            Message m = Message.obtain();
            m.setData(intent.getExtras());
            handler.sendMessage(m);
        });
        t.start();
    }

    private static Intent updateStatus(Context context, String VIN) {
        Intent data = new Intent();
        UserInfoDao userDao = UserInfoDatabase.getInstance(context)
                .userInfoDao();
        VehicleInfoDao infoDao = VehicleInfoDatabase.getInstance(context)
                .vehicleInfoDao();

        VehicleInfo vehInfo = infoDao.findVehicleInfoByVIN(VIN);
        UserInfo userInfo = userDao.findUserInfo(vehInfo.getUserId());
        String token = userInfo.getAccessToken();

        if (!MainActivity.checkInternetConnection(context)) {
            data.putExtra("action", COMMAND_NO_NETWORK);
        } else {
            USAPICVService commandServiceClient = NetworkServiceGenerators.createUSAPICVService(USAPICVService.class, context);
            Call<CommandStatus> call;
            call = commandServiceClient.putStatus(token, Constants.APID, VIN);
            try {
                Response<CommandStatus> response = call.execute();
                if (response.isSuccessful()) {
                    CommandStatus status = response.body();
                    if (status.getStatus() == CMD_STATUS_SUCCESS) {
                        LogFile.i(context, MainActivity.CHANNEL_ID, "statusrefresh send successful.");
                        Looper.prepare();
                        Toast.makeText(context, "Command transmitted.", Toast.LENGTH_SHORT).show();
                        data.putExtra("action", pollStatus(context, token, vehInfo, status.getCommandId()));
                    } else if (status.getStatus() == CMD_REMOTE_START_LIMIT) {
                        LogFile.i(context, MainActivity.CHANNEL_ID, "statusrefresh send UNSUCCESSFUL.");
                        data.putExtra("action", COMMAND_REMOTE_START_LIMIT);
                    } else {
                        data.putExtra("action", COMMAND_EXCEPTION);
                        LogFile.i(context, MainActivity.CHANNEL_ID, "statusrefresh send unknown response.");
                        LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                    }
                } else {
                    data.putExtra("action", COMMAND_FAILED);
                    LogFile.i(context, MainActivity.CHANNEL_ID, "statusrefresh send UNSUCCESSFUL.");
                    LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                }
            } catch (Exception e) {
                data.putExtra("action", COMMAND_EXCEPTION);
                LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.updateStatus(): ", e);
            }
        }
        return data;
    }

    private static String pollStatus(Context context, String token, VehicleInfo vehInfo, String idCode) {
        // Delay 5 seconds before starting to check on status
        try {
            Thread.sleep(10 * 1000);
        } catch (InterruptedException e) {
            LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.pollStatus: ", e);
        }

        String VIN = vehInfo.getVIN();
        USAPICVService commandServiceClient = NetworkServiceGenerators.createUSAPICVService(USAPICVService.class, context);
        try {
            for (int retries = 0; retries < 10; ++retries) {
                Call<CarStatus> call = commandServiceClient.pollStatus(token, Constants.APID, VIN, idCode);
                Response<CarStatus> response = call.execute();
                if (response.isSuccessful()) {
                    CarStatus status = response.body();
                    switch (status.getStatus()) {
                        case CMD_STATUS_SUCCESS:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "poll response successful.");
                            long now = Instant.now().toEpochMilli();
                            vehInfo.setLastForcedRefreshTime(now);
                            long count = vehInfo.getForcedRefreshCount();
                            if (count == 0) {
                                vehInfo.setInitialForcedRefreshTime(now);
                            }
                            vehInfo.setForcedRefreshCount(++count);
                            VehicleInfoDatabase.getInstance(context).vehicleInfoDao().updateVehicleInfo(vehInfo);
                            return COMMAND_SUCCESSFUL;
                        case CMD_STATUS_FAILED:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "poll response failed.");
                            return COMMAND_FAILED;
                        case CMD_STATUS_INPROGRESS:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "poll response waiting.");
                            break;
                        default:
                            LogFile.i(context, MainActivity.CHANNEL_ID, "poll response unknown: status = " + status.getStatus());
                            return COMMAND_FAILED;
                    }
                } else {
                    LogFile.i(context, MainActivity.CHANNEL_ID, response.raw().toString());
                    LogFile.i(context, MainActivity.CHANNEL_ID, "poll response UNSUCCESSFUL.");
                    return COMMAND_FAILED;
                }
                Thread.sleep(3 * 1000);
            }
            LogFile.i(context, MainActivity.CHANNEL_ID, "poll timeout?");
            return COMMAND_FAILED;
        } catch (Exception e) {
            LogFile.e(context, MainActivity.CHANNEL_ID, "exception in NetworkCalls.pollStatus(): ", e);
            return COMMAND_EXCEPTION;
        }
    }

}
