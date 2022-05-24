package com.example.khughes.machewidget;

import android.content.Context;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.example.khughes.machewidget.db.UserInfoDao;
import com.example.khughes.machewidget.db.UserInfoDatabase;
import com.example.khughes.machewidget.db.VehicleInfoDao;
import com.example.khughes.machewidget.db.VehicleInfoDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

public class InfoRepository {

    private final VehicleInfoDao mVehicleInfoDao;
    private final UserInfoDao mUserInfoDao;
    private final Context mContext;
    private final String mVIN_key;

    private List<VehicleInfo> mVehicleList;
    private VehicleInfo mVehicleInfo;
    private final List<UserInfo> mUserList;
    private UserInfo mUserInfo;

    InfoRepository(Context context) {
        mContext = context;
        mVehicleInfoDao = VehicleInfoDatabase.getInstance(context).vehicleInfoDao();
        mUserInfoDao = UserInfoDatabase.getInstance(context).userInfoDao();
        mVIN_key = context.getResources().getString(R.string.VIN_key);

        mVehicleList = mVehicleInfoDao.findVehicleInfo();
        mUserList = mUserInfoDao.findUserInfo();

        String VIN = PreferenceManager.getDefaultSharedPreferences(context).getString(mVIN_key, null);
        mVehicleInfo = new VehicleInfo();
        mUserInfo = new UserInfo();
        if (VIN != null) {
            mVehicleInfo = mVehicleInfoDao.findVehicleInfoByVIN(VIN);
            if (mVehicleInfo != null) {
                String userId = mVehicleInfo.getUserId();
                if (userId != null) {
                    mUserInfo = mUserInfoDao.findUserInfo(mVehicleInfo.getUserId());
                } else {
                    LogFile.e(context, MainActivity.CHANNEL_ID, "InfoRepository(): userId " + mVehicleInfo.getUserId() +
                            " not found in database for VIN " + VIN);
                    List<UserInfo> users = mUserInfoDao.findUserInfo();
                    if (users.size() > 0) {
                        mUserInfo = users.get(0);
                        LogFile.e(context, MainActivity.CHANNEL_ID, "InfoRepository(): fallback to userId " + mUserInfo.getUserId());
                    }
                }
            }
        }
    }

    public VehicleInfo getVehicle() {
        return mVehicleInfo;
    }

    public void setVehicle(VehicleInfo info) {
        mVehicleInfo = info;
        VehicleInfoDatabase.databaseWriteExecutor.execute(() -> {
            mVehicleInfoDao.updateVehicleInfo(info);
        });
    }

    public UserInfo getUser() {
        return mUserInfo;
    }

//    public void updateProfiles(String userId) {
//        // Same user
//        if (mUserInfo.getUserId().equals(userId)) {
//            // Right now, anytime a user logs in a new entry is added to the Vehicle database for each vehicle.  This is
//            // because the list associated with the user ID might change.  So we find all the vehicles in this list and
//            // delete the old copies from the database.
//            for (VehicleInfo vehicle : mVehicleList) {
//                if (vehicle.getUserId().equals(userId)) {
//                    VehicleInfoDatabase.databaseSingleThread.submit(() -> {
//                        mVehicleInfoDao.deleteVehicleInfo(vehicle);
//                    });
//                }
//            }
//        }
//        // New user
//        else {
//            // Delete all the old vehicles
//            VehicleInfoDatabase.databaseSingleThread.submit(() -> {
//                mVehicleInfoDao.deleteVehicleInfoByUserId(mUserInfo.getUserId());
//                // Delete the user
//                mUserInfoDao.deleteUserInfo(mUserInfo);
//                // Manually update the local list
//                mUserList.remove(mUserInfo);
//                mUserInfo = mUserInfoDao.findUserInfo(userId);
//                mUserList.add(mUserInfo);
//            });
//        }
//
//        // Update the vehicle list
//        VehicleInfoDatabase.databaseSingleThread.submit(() -> {
//            mVehicleList = mVehicleInfoDao.findVehicleInfo();
//            String VIN = PreferenceManager.getDefaultSharedPreferences(mContext).getString(mVIN_key, null);
//
//            // Look for the current VIN in the up-to-date vehicle list
//            boolean foundVIN = false;
//            for (VehicleInfo vehicle : mVehicleList) {
//                if (vehicle.getVIN().equals(VIN)) {
//                    foundVIN = true;
//                }
//            }
//            // We need to change the current VIN to something.
//            if (!foundVIN) {
//                if (mVehicleList.isEmpty()) {
//                    VIN = "";
//                } else {
//                    VIN = mVehicleList.get(0).getVIN();
//                }
//                PreferenceManager.getDefaultSharedPreferences(mContext).edit().putString(mVIN_key, VIN).commit();
//                LogFile.d(mContext,MainActivity.CHANNEL_ID, "setting VIN to "+VIN);
//            }
//        });
//    }

}

