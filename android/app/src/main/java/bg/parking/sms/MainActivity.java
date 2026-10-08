package bg.parking.sms;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final String PREFS = "parking";
    static final String DEFAULT_BLUE = "1302";
    static final String DEFAULT_GREEN = "1303";
    static final int DEFAULT_MINUTES = 60;

    private static final String ACTION_SENT = "bg.parking.sms.SMS_SENT";
    private static final int REQ_SMS = 1;
    private static final Pattern BG_PLATE = Pattern.compile("^[A-Z]{1,2}\\d{4}[A-Z]{1,2}$");

    private SharedPreferences prefs;
    private EditText plateInput;
    private LinearLayout platesBox;
    private Button blueButton, greenButton;
    private View statusBox;
    private TextView statusText, timerText;
    private EditText cfgBlue, cfgGreen, cfgMinutes;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, 1000);
        }
    };

    // Изпращане, което чака разрешение за СМС.
    private String pendingZone, pendingPlate;

    private final BroadcastReceiver sentReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String zone = intent.getStringExtra("zone");
            String plate = intent.getStringExtra("plate");
            if (getResultCode() == Activity.RESULT_OK) {
                Toast.makeText(context, "СМС изпратен: " + plate, Toast.LENGTH_LONG).show();
                startSession(zone, plate);
            } else {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("СМС не е изпратен")
                        .setMessage("Проверете дали имате сигнал и кредит. Код на грешка: " + getResultCode())
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        plateInput = findViewById(R.id.plate);
        platesBox = findViewById(R.id.plates);
        blueButton = findViewById(R.id.blue);
        greenButton = findViewById(R.id.green);
        statusBox = findViewById(R.id.status);
        statusText = findViewById(R.id.statusText);
        timerText = findViewById(R.id.timer);
        cfgBlue = findViewById(R.id.cfgBlue);
        cfgGreen = findViewById(R.id.cfgGreen);
        cfgMinutes = findViewById(R.id.cfgMinutes);

        plateInput.setText(prefs.getString("current", ""));
        cfgBlue.setText(zoneNumber("blue"));
        cfgGreen.setText(zoneNumber("green"));
        cfgMinutes.setText(String.valueOf(minutes()));

        saveOnChange(cfgBlue, "cfg.blue");
        saveOnChange(cfgGreen, "cfg.green");
        saveOnChange(cfgMinutes, "cfg.minutes");

        blueButton.setOnClickListener(v -> confirmAndSend("blue", plateInput.getText().toString()));
        greenButton.setOnClickListener(v -> confirmAndSend("green", plateInput.getText().toString()));
        findViewById(R.id.extend).setOnClickListener(v -> confirmAndSend(
                prefs.getString("session.zone", "blue"), prefs.getString("session.plate", "")));
        findViewById(R.id.clear).setOnClickListener(v -> {
            prefs.edit().remove("session.zone").remove("session.plate").remove("session.end").apply();
            ReminderReceiver.cancel(this);
            renderStatus();
        });

        IntentFilter filter = new IntentFilter(ACTION_SENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sentReceiver, filter);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        renderPlates();
        renderZoneButtons();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(sentReceiver);
    }

    // --- Настройки ---

    String zoneNumber(String zone) {
        String def = zone.equals("blue") ? DEFAULT_BLUE : DEFAULT_GREEN;
        String v = prefs.getString("cfg." + zone, def).trim();
        return v.isEmpty() ? def : v;
    }

    int minutes() {
        try {
            int m = Integer.parseInt(prefs.getString("cfg.minutes", "").trim());
            return m > 0 ? m : DEFAULT_MINUTES;
        } catch (NumberFormatException e) {
            return DEFAULT_MINUTES;
        }
    }

    private void saveOnChange(EditText field, String key) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
                renderZoneButtons();
            }
        });
    }

    private void renderZoneButtons() {
        blueButton.setText("Синя зона\nСМС до " + zoneNumber("blue"));
        greenButton.setText("Зелена зона\nСМС до " + zoneNumber("green"));
    }

    // --- Регистрационни номера ---

    // Българските номера са с латински букви, затова превеждаме сходните кирилски.
    static String normalize(String s) {
        String from = "АВЕКМНОРСТХУ";
        String to = "ABEKMHOPCTXY";
        StringBuilder out = new StringBuilder();
        for (char c : s.toUpperCase(Locale.ROOT).toCharArray()) {
            int i = from.indexOf(c);
            if (i >= 0) c = to.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) out.append(c);
        }
        return out.toString();
    }

    private List<String> savedPlates() {
        String raw = prefs.getString("plates", "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) list.addAll(Arrays.asList(raw.split(",")));
        return list;
    }

    private void storePlates(List<String> list) {
        prefs.edit().putString("plates", String.join(",", list)).apply();
    }

    private void renderPlates() {
        platesBox.removeAllViews();
        for (String p : savedPlates()) {
            Button chip = new Button(this);
            chip.setText(p);
            chip.setAllCaps(false);
            chip.setOnClickListener(v -> {
                plateInput.setText(p);
                prefs.edit().putString("current", p).apply();
            });
            chip.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage("Да изтрия ли " + p + "?")
                        .setPositiveButton("Изтрий", (d, w) -> {
                            List<String> list = savedPlates();
                            list.remove(p);
                            storePlates(list);
                            renderPlates();
                        })
                        .setNegativeButton("Отказ", null)
                        .show();
                return true;
            });
            platesBox.addView(chip);
        }
    }

    // --- Изпращане ---

    private void confirmAndSend(String zone, String rawPlate) {
        String plate = normalize(rawPlate);
        if (plate.isEmpty()) {
            Toast.makeText(this, "Въведете регистрационен номер", Toast.LENGTH_SHORT).show();
            return;
        }
        plateInput.setText(plate);
        String zoneName = zone.equals("blue") ? "Синя зона" : "Зелена зона";
        String msg = "Изпращане на СМС до " + zoneNumber(zone) + " с текст:\n\n" + plate;
        if (!BG_PLATE.matcher(plate).matches()) {
            msg += "\n\n⚠️ Номерът не прилича на български регистрационен номер.";
        }
        new AlertDialog.Builder(this)
                .setTitle(zoneName)
                .setMessage(msg)
                .setPositiveButton("Изпрати", (d, w) -> sendWithPermission(zone, plate))
                .setNegativeButton("Отказ", null)
                .show();
    }

    private void sendWithPermission(String zone, String plate) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            sendSms(zone, plate);
        } else {
            pendingZone = zone;
            pendingPlate = plate;
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQ_SMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_SMS || pendingZone == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            sendSms(pendingZone, pendingPlate);
        } else {
            Toast.makeText(this, "Без разрешение за СМС приложението не може да плаща паркиране",
                    Toast.LENGTH_LONG).show();
        }
        pendingZone = null;
        pendingPlate = null;
    }

    private void sendSms(String zone, String plate) {
        List<String> list = savedPlates();
        if (!list.contains(plate)) {
            list.add(plate);
            storePlates(list);
            renderPlates();
        }
        prefs.edit().putString("current", plate).apply();

        Intent sent = new Intent(ACTION_SENT)
                .setPackage(getPackageName())
                .putExtra("zone", zone)
                .putExtra("plate", plate);
        PendingIntent sentPi = PendingIntent.getBroadcast(this, 0, sent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            SmsManager sms = Build.VERSION.SDK_INT >= 31
                    ? getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            sms.sendTextMessage(zoneNumber(zone), null, plate, sentPi, null);
            Toast.makeText(this, "Изпращане…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Грешка")
                    .setMessage("СМС не може да бъде изпратен: " + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    // --- Таймер ---

    private void startSession(String zone, String plate) {
        long now = System.currentTimeMillis();
        long oldEnd = prefs.getLong("session.end", 0);
        boolean extending = plate.equals(prefs.getString("session.plate", null))
                && zone.equals(prefs.getString("session.zone", null))
                && oldEnd > now;
        long end = (extending ? oldEnd : now) + minutes() * 60_000L;
        prefs.edit()
                .putString("session.zone", zone)
                .putString("session.plate", plate)
                .putLong("session.end", end)
                .apply();
        ReminderReceiver.schedule(this, end);
        renderStatus();
    }

    private void renderStatus() {
        long end = prefs.getLong("session.end", 0);
        if (end == 0) {
            statusBox.setVisibility(View.GONE);
            return;
        }
        statusBox.setVisibility(View.VISIBLE);
        String zone = prefs.getString("session.zone", "blue");
        String zoneName = zone.equals("blue") ? "Синя зона" : "Зелена зона";
        String endStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(end));
        statusText.setText(zoneName + " · " + prefs.getString("session.plate", "") + " · до " + endStr);

        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            timerText.setText("Изтекло!");
            timerText.setTextColor(Color.parseColor("#DC2626"));
            return;
        }
        long m = left / 60_000, s = (left % 60_000) / 1000;
        timerText.setText(String.format(Locale.ROOT, "%d:%02d", m, s));
        timerText.setTextColor(left < 5 * 60_000 ? Color.parseColor("#DC2626") : statusText.getCurrentTextColor());
    }
}
