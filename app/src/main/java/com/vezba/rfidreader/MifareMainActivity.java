package com.vezba.rfidreader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MifareMainActivity extends Activity {
    private static final String TAG = MifareMainActivity.class.getSimpleName();
    private static final String LINE_SEPARATOR = System.getProperty("line.separator", "\n");

    private boolean mDebug = true;

    private String mCharset = "ISO-8859-1";
    private boolean mShowDataAsHexString;
    private boolean mReadAll;

    private boolean mWriteToBlock;
    private boolean mWriteAll;

    private NfcAdapter mAdapter;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mIntentFilters;
    private String[][] mTechList;

    private EditText mReadBlockIndex;
    private RadioGroup mReadOptions;
    private CheckBox mReadAllData;

    private EditText mWriteBlockContent;
    private RadioGroup mWriteOptions;
    private EditText mWriteBlockIndex;

    private CheckBox mDebugCheckBox;

    private RelativeLayout mWriteBlockIndexLayout;

    JsonData podaci;
    private FusedLocationProviderClient fusedLocationClient;
    private int locationRequestCode = 1000;
    private double wayLatitude = 0.0;
    private double wayLongitude = 0.0;
    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Button send_data;
    String myUrl ="https://rfidrestservice.herokuapp.com/api/rfid";

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(10 * 1000);



        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        wayLatitude = location.getLatitude();
                        wayLongitude = location.getLongitude();
                        Toast.makeText(MifareMainActivity.this, "" +wayLatitude, Toast.LENGTH_SHORT).show();

                    }
                }
            }
        };


        mAdapter = NfcAdapter.getDefaultAdapter(this);
        if (mAdapter == null) {
            this.finish();
            return;
        }

        mPendingIntent = PendingIntent.getActivity(this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        try {
            filter.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            e.printStackTrace();
        }
        mIntentFilters = new IntentFilter[]{filter};
        mTechList = new String[][]{new String[]{MifareClassic.class.getName()}};

        initViews();

        send_data = findViewById(R.id.send_data);

        send_data.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               // new JsonPost().execute("http://192.168.1.3:8080/api/rfid", json);
                try {
                    new HttpAsyncTask().execute("https://rfidrestservice.herokuapp.com/api/rfid", toJson().toString());

                }catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        });
    }


    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.enableForegroundDispatch(this, mPendingIntent, mIntentFilters, mTechList);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            return;
        }
        resolveIntent(intent);
    }

    private void resolveIntent(Intent intent) {
        debug("resolveIntent...", false);
        if (getText(mReadBlockIndex) == null && getText(mWriteBlockContent) == null && !mReadAll) {
            debug(getString(R.string.toast_need_data), true);
            return;
        }

        MifareClassic mfc = MifareClassic.get((Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));
        if (mfc == null) {
            debug(getString(R.string.toast_not_mifare_classic), true);
            return;
        }

        disableAllOptions();
        try {
            String result1 = performRead(mfc);
            if (result1 != null) {
                if (mReadAll) {
                    alert(result1);
                } else {
                    debug(result1, true);
                }
            }

            List<Integer> result2 = performWrite(mfc);
            if (result2 != null) {
                if (result2.size() == 0) {
                    debug(getString(R.string.toast_write_success), true);
                } else if (result2.size() == 1 && !mReadAll) {
                    debug(getString(R.string.toast_write_fail), true);
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append(getString(R.string.result_failed_the_following));
                    sb.append(LINE_SEPARATOR);
                    for (int i = 0; i < result2.size(); i++) {
                        sb.append(result2.get(i));
                        if (i != 0 && i % 5 == 0) {
                            sb.append(LINE_SEPARATOR);
                        } else {
                            sb.append(", ");
                        }
                    }
                    sb.delete(sb.length() - 2, sb.length());
                    alert(sb.toString());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            enableAllOptions();
            try {
                mfc.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private String performRead(MifareClassic mfc) throws IOException {
        if (getText(mReadBlockIndex) == null && !mReadAll) {
            return null;
        }
        if (!mfc.isConnected()) {
            mfc.connect();
        }

        String ret = null;
        if (mReadAll) {
            StringBuilder sb = new StringBuilder();
            boolean auth;
            int blockCount = mfc.getBlockCount();
            podaci = new JsonData();
            for (int i = 1; i < blockCount; i++) {
                auth = mfc.authenticateSectorWithKeyA(mfc.blockToSector(i), MifareClassic.KEY_DEFAULT);
                if (auth) {
                    if ((i + 1) % 4 != 0) {
                        byte[] data = readBlock(mfc, i);
                        if (!convertBytes2String(data).isEmpty()) {
                            sb.append(String.format(getString(R.string.block_index_dynamic), i));
                            sb.append(LINE_SEPARATOR);
                            sb.append(convertBytes2String(data));
                            sb.append(LINE_SEPARATOR);
                            if ((i % 4) == 0) {
                                podaci.setIme(convertBytes2String(data).substring(0, 5).trim());
                                System.out.println("Ime: " + podaci.getIme());
                                podaci.setDatum(convertBytes2String(data).substring(6).trim());
                                System.out.println("Datum: " + podaci.getDatum());
                            }
                            if ((i % 2) != 0) {
                                podaci.setZemlja(convertBytes2String(data).substring(1).trim());
                                System.out.println("Zemlja: " + podaci.getZemlja());
                            }
                            sb.append(LINE_SEPARATOR);
                            sb.append(LINE_SEPARATOR);
                        }
                    }
                }
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        locationRequestCode);
            } else {
                fusedLocationClient.getLastLocation()
                        .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                            @Override
                            public void onSuccess(Location location) {
                                // Got last known location. In some rare situations this can be null.
                                if (location != null) {
                                    wayLatitude = location.getLatitude();
                                    wayLongitude = location.getLongitude();
                                    System.out.println("Latitude: " + wayLatitude + "Longitude: " + wayLongitude);
                                }
                            }
                        });

            }
            sb.delete(sb.length() - 2, sb.length());
            ret = sb.toString();
            try {
                System.out.println(stringJson(toJson()));

            }catch (JSONException e) { e.printStackTrace();}
        } else {
            int blockIndex = Integer.parseInt(getText(mReadBlockIndex));
            if (!validateBlockIndex(mfc, blockIndex)) {
                debug(getString(R.string.err_block_index_out_of_bound), true);
                return ret;
            }
            int sectorIndex = mfc.blockToSector(blockIndex);
            boolean auth = mfc.authenticateSectorWithKeyA(sectorIndex, MifareClassic.KEY_DEFAULT);
            if (auth && readBlock(mfc, blockIndex) != null) {
                byte[] data = readBlock(mfc, blockIndex);
                ret = convertBytes2String(data);
            } else {
                debug(getString(R.string.auth_failed), true);
            }
        }
        return ret;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case 1000: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // TODO: Consider calling
                        //    Activity#requestPermissions
                        // here to request the missing permissions, and then overriding
                        //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                        //                                          int[] grantResults)
                        // to handle the case where the user grants the permission. See the documentation
                        // for Activity#requestPermissions for more details.
                        return;
                    }
                    fusedLocationClient.getLastLocation()
                            .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                                @Override
                                public void onSuccess(Location location) {
                                    // Got last known location. In some rare situations this can be null.
                                    if (location != null) {
                                        wayLatitude = location.getLatitude();
                                        wayLongitude = location.getLongitude();

                                    }
                                }
                            });
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }
    }

    private List<Integer> performWrite(MifareClassic mfc) throws IOException {
        if (getText(mWriteBlockContent) == null) {
            return null;
        }

        if (!mfc.isConnected()) {
            mfc.connect();
        }

        List<Integer> ret = new ArrayList<Integer>();
        String content = getText(mWriteBlockContent);
        if (mWriteAll) {
            for (int i = 1; i < mfc.getBlockCount(); i++) {
                if ((i + 1) % 4 == 0) {
                    continue;
                }
                boolean auth = mfc.authenticateSectorWithKeyA(mfc.blockToSector(i), MifareClassic.KEY_DEFAULT);
                if (auth) {
                    writeBlock(mfc, i, convertString2Bytes(content));
                } else {
                    ret.add(i);
                }
            }
        } else {
            int blockIndex;
            if (mWriteToBlock && getText(mWriteBlockIndex) != null) {
                blockIndex = Integer.parseInt(getText(mWriteBlockIndex));
                if (!validateBlockIndex(mfc, blockIndex)) {
                    debug(getString(R.string.err_block_index_out_of_bound), true);
                    return null;
                }
                if (blockIndex == 0 || (blockIndex + 1) % 4 == 0) {
                    debug(getString(R.string.err_write_to_forbidden_block), true);
                    return null;
                }
            } else {
                blockIndex = randomBlockIndex(mfc);
            }
            debug(String.format(getString(R.string.write_random_dynamic), blockIndex), false);
            boolean auth = mfc.authenticateSectorWithKeyA(mfc.blockToSector(blockIndex), MifareClassic.KEY_DEFAULT);
            if (auth) {
                writeBlock(mfc, blockIndex, convertString2Bytes(content));
            } else {
                ret.add(blockIndex);
                debug(getString(R.string.auth_failed), true);
            }
        }
        return ret;
    }

    private byte[] readBlock(MifareClassic mfc, int blockIndex) throws IOException {
        return mfc.readBlock(blockIndex);
    }

    private void writeBlock(MifareClassic mfc, int blockIndex, byte[] data) throws IOException {
        mfc.writeBlock(blockIndex, data);
    }

    private String convertBytes2String(byte[] data) throws UnsupportedEncodingException {
        String ret;
        if (mShowDataAsHexString) {
            StringBuilder sb = new StringBuilder();
            for (byte b : data) {
                int i = (int) b;
                sb.append(Integer.toHexString(i).toUpperCase());
            }
            ret = sb.toString();
        } else {
            int pos = data.length;
            for (int i = data.length - 1; i >= 0; i--) {
                if (data[i] != 0) {
                    break;
                }
                pos = i;
            }
            ret = new String(data, 0, pos, mCharset);
        }
        return ret;
    }

    private byte[] convertString2Bytes(String content) throws UnsupportedEncodingException {
        byte[] ret = new byte[16];
        byte[] buf = content.getBytes(mCharset);
        int retLen = ret.length;
        int bufLen = buf.length;
        boolean b = retLen > bufLen;

        for (int i = 0; i < retLen; i++) {
            if (b && i >= bufLen) {
                ret[i] = 0;
                continue;
            }
            ret[i] = buf[i];
        }
        return ret;
    }

    private String getText(EditText et) {
        if (TextUtils.isEmpty(et.getText())) {
            return null;
        }
        return et.getText().toString().trim();
    }

    private int randomBlockIndex(MifareClassic mfc) {
        int i = new Random().nextInt(mfc.getBlockCount());
        if (i == 0 || (i + 1) % 4 == 0) {
            return randomBlockIndex(mfc);
        }
        return i;
    }

    private boolean validateBlockIndex(MifareClassic mfc, int blockIndex) {
        if (blockIndex >= mfc.getBlockCount()) {
            return false;
        }
        return true;
    }

    private void alert(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).show();
    }

    private void enableAllOptions() {
        mReadBlockIndex.setEnabled(true);
        for (int i = 0; i < mReadOptions.getChildCount(); i++) {
            mReadOptions.getChildAt(i).setEnabled(true);
        }
        mReadAllData.setEnabled(true);

        mWriteBlockContent.setEnabled(true);
        for (int i = 0; i < mWriteOptions.getChildCount(); i++) {
            mWriteOptions.getChildAt(i).setEnabled(true);
        }
        mWriteBlockIndex.setEnabled(true);

        mDebugCheckBox.setEnabled(true);
    }

    private void disableAllOptions() {
        mReadBlockIndex.setEnabled(false);
        for (int i = 0; i < mReadOptions.getChildCount(); i++) {
            mReadOptions.getChildAt(i).setEnabled(false);
        }
        mReadAllData.setEnabled(false);

        mWriteBlockContent.setEnabled(false);
        for (int i = 0; i < mWriteOptions.getChildCount(); i++) {
            mWriteOptions.getChildAt(i).setEnabled(false);
        }
        mWriteBlockIndex.setEnabled(false);

        mDebugCheckBox.setEnabled(false);
    }

    public void clearText(View view) {
        switch (view.getId()) {
            case R.id.btn_et_read_block_index:
                mReadBlockIndex.setText(null);
                break;

            case R.id.btn_write_block_content:
                mWriteBlockContent.setText(null);
                break;

            case R.id.btn_write_block_index:
                mWriteBlockIndex.setText(null);
                break;
        }
    }

    private void initViews() {
        mReadBlockIndex = (EditText) findViewById(R.id.et_read_block_index);
        mReadOptions = (RadioGroup) findViewById(R.id.rg_read_options);
        mReadAllData = (CheckBox) findViewById(R.id.cb_read_all_data);

        mWriteBlockContent = (EditText) findViewById(R.id.et_write_block_content);
        mWriteOptions = (RadioGroup) findViewById(R.id.rg_write_options);
        mWriteBlockIndex = (EditText) findViewById(R.id.et_write_block_index);

        mDebugCheckBox = (CheckBox) findViewById(R.id.cb_debug);

        mWriteBlockIndexLayout = (RelativeLayout) findViewById(R.id.rl_write_block_index);

        mReadAllData.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mReadAll = isChecked;
            }
        });

        mReadOptions.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rb_charset_ascii:
                        mCharset = "ISO-8859-1";
                        mShowDataAsHexString = false;
                        break;

                    case R.id.rb_charset_utf8:
                        mCharset = "UTF-8";
                        mShowDataAsHexString = false;
                        break;

                    case R.id.rb_hex:
                        mCharset = "ISO-8859-1";
                        mShowDataAsHexString = true;
                        break;
                }
            }
        });

        mWriteOptions.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                switch (checkedId) {
                    case R.id.rb_write_block_index:
                        mWriteBlockIndexLayout.setVisibility(View.VISIBLE);
                        mWriteToBlock = true;
                        mWriteAll = false;
                        break;

                    case R.id.rb_write_all_data:
                        mWriteBlockIndexLayout.setVisibility(View.GONE);
                        mWriteToBlock = false;
                        mWriteAll = true;
                        break;
                }
            }
        });
        mDebugCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mDebug = !isChecked;
            }
        });
    }

    private void debug(String info, boolean toast) {
        if (mDebug) {
            Log.i(TAG, info);
            if (toast) {
                showToast(info);
            }
        }
    }

    private void showToast(String toast) {
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    public JSONObject toJson() throws JSONException{
         JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("Zemlja:", podaci.getZemlja());
            jsonObject.put("Ime", podaci.getIme());
            jsonObject.put("Datum", podaci.getDatum());
            jsonObject.put("Lat: ", wayLatitude);
            jsonObject.put("Lon: ", wayLongitude);
            return jsonObject;
        } catch (JSONException e) {
            e.printStackTrace();
            Log.d(TAG, "toJson: cant make JsonObj");
            return null;
        }

    }



    private String stringJson(JSONObject jsonObject){
        return jsonObject.toString();
    }

    public boolean checkNetworkConnection() {
        ConnectivityManager connMgr = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        boolean isConnected = false;

        return isConnected;
    }

    private class HttpAsyncTask extends AsyncTask<String, Void, String>{

        @Override
        protected String doInBackground(String... urls) {
            try {
                try {
                    return HttpPost(urls[0]);
                } catch (JSONException e) {
                    e.printStackTrace();
                    return "Error!";
                }
            } catch (IOException e) {
                return "Unable to retrieve web page. URL may be invalid.";
            }
        }

        @Override
        protected void onPostExecute(String result) {
            System.out.println("rezultat" +result);
        }
    }

    private JSONObject buildJsonObject() throws JSONException{
        JSONObject jsonObj = new JSONObject();
        jsonObj.accumulate("zemlja", podaci.getZemlja());
        jsonObj.accumulate("ime", podaci.getIme());
        jsonObj.accumulate("datum", podaci.getDatum());
        jsonObj.accumulate("lat", wayLatitude);
        jsonObj.accumulate("lon", wayLongitude);

        return jsonObj;
    }

    private String HttpPost(String myUrl) throws IOException,JSONException {
        String result = "";

        URL url = new URL(myUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");

       JSONObject jsonObj = buildJsonObject();
        setPostRequestContent(conn, jsonObj);
        conn.connect();
        return conn.getResponseMessage()+"";
    }

    private void setPostRequestContent(HttpURLConnection conn,
                                       JSONObject jsonObj) throws IOException {

        OutputStream os = conn.getOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
        writer.write(jsonObj.toString());
        Log.i(MifareMainActivity.class.toString(), jsonObj.toString());
        writer.flush();
        writer.close();
        os.close();
    }



}

