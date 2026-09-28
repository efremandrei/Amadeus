package com.efremandrei.amadeus;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Branded launch moment that introduces the loop-and-waveform visual identity. */
public final class SplashActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 9, 24));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 24));
        setContentView(R.layout.activity_splash);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, 900);
    }
}
