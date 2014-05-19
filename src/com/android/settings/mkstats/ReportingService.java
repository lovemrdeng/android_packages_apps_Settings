/*
 * Copyright (C) 2014 The MoKee OpenSource Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.mkstats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;

import com.android.settings.R;
import com.google.analytics.tracking.android.GoogleAnalytics;
import com.google.analytics.tracking.android.Tracker;

public class ReportingService extends Service {
    protected static final String TAG = ReportingService.class.getSimpleName();

    private StatsUploadTask mTask;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId) {
        Log.d(TAG, "User has opted in -- reporting.");

        if (mTask == null || mTask.getStatus() == AsyncTask.Status.FINISHED) {
            mTask = new StatsUploadTask();
            mTask.execute();
        }

        return Service.START_REDELIVER_INTENT;
    }

    private class StatsUploadTask extends AsyncTask<Void, Void, Boolean> {

        private InputStream is;

        @Override
        protected Boolean doInBackground(Void... params) {
            final Context context = ReportingService.this;
            String deviceId = Utilities.getUniqueID(context);
            String deviceName = Utilities.getDevice();
            String deviceVersion = Utilities.getModVersion();
            String deviceCountry = Utilities.getCountryCode(context);
            String deviceCarrier = Utilities.getCarrier(context);
            String deviceCarrierId = Utilities.getCarrierId(context);

            Log.d(TAG, "SERVICE: Device ID=" + deviceId);
            Log.d(TAG, "SERVICE: Device Name=" + deviceName);
            Log.d(TAG, "SERVICE: Device Version=" + deviceVersion);
            Log.d(TAG, "SERVICE: Country=" + deviceCountry);
            Log.d(TAG, "SERVICE: Carrier=" + deviceCarrier);
            Log.d(TAG, "SERVICE: Carrier ID=" + deviceCarrierId);

            // report to google analytics
            GoogleAnalytics ga = GoogleAnalytics.getInstance(context);
            Tracker tracker = ga.getTracker(getString(R.string.ga_trackingId));
            tracker.sendEvent(deviceName, deviceVersion, deviceCountry, null);

            // this really should be set at build time...
            // format of version should be:
            // version[-date-type]-device
            String[] parts = deviceVersion.split("-");
            String deviceVersionNoDevice = null;
            if (parts.length == 2) {
                deviceVersionNoDevice = parts[0];
            } else if (parts.length == 4) {
                deviceVersionNoDevice = parts[0] + "-" + parts[2];
            }

            if (deviceVersionNoDevice != null) {
                tracker.sendEvent("checkin", deviceName, deviceVersionNoDevice, null);
            }
            tracker.close();

            // report to the mkstats service
            HttpClient httpClient = new DefaultHttpClient();
            HttpPost httpPost = new HttpPost("http://stats.mokeedev.com/index.php/Submit/flash");
            boolean success = false;

            try {
                List<NameValuePair> kv = new ArrayList<NameValuePair>(5);
                kv.add(new BasicNameValuePair("device_hash", deviceId));
                kv.add(new BasicNameValuePair("device_name", deviceName));
                kv.add(new BasicNameValuePair("device_version", deviceVersion));
                kv.add(new BasicNameValuePair("device_country", deviceCountry));
                kv.add(new BasicNameValuePair("device_carrier", deviceCarrier));
                kv.add(new BasicNameValuePair("device_carrier_id", deviceCarrierId));

                httpPost.setEntity(new UrlEncodedFormEntity(kv));
                is = httpClient.execute(httpPost).getEntity().getContent();

                success = true;
            } catch (IOException e) {
                Log.w(TAG, "Could not upload stats checkin", e);
            }

            return success;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            final Context context = ReportingService.this;
            long interval;

            if (result) {
                String deviceMoKeeVersion = Utilities.getMoKeeVersion();
                final SharedPreferences prefs = getSharedPreferences(ReportingServiceManager.ANONYMOUS_PREF, 0);
                long device_flash_time = 0;
                try {
                    device_flash_time = Long.valueOf(convertStreamToJSONObject(is).getString("device_flash_time"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
                prefs.edit().putLong(ReportingServiceManager.ANONYMOUS_LAST_CHECKED,
                        System.currentTimeMillis()).putLong(ReportingServiceManager.ANONYMOUS_FLASH_TIME,
                                    device_flash_time).putString(ReportingServiceManager.DEVICE_MOKEE_VERSION, deviceMoKeeVersion).apply();
                // use set interval
                interval = 0;
            } else {
                // error, try again in 3 hours
                interval = 3L * 60L * 60L * 1000L;
            }

            ReportingServiceManager.setAlarm(context, interval);
            stopSelf();
        }
    }

    private JSONObject convertStreamToJSONObject(InputStream is) throws IOException, JSONException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        for (String str = reader.readLine(); str != null; str = reader.readLine())
        {
            builder.append(str);
        }
        return new JSONObject(builder.toString());

    }
}
