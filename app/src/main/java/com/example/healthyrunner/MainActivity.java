package com.example.healthyrunner;

import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen and keep the screen on while playing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        FrameLayout container = findViewById(R.id.gameContainer);
        gameView = new GameView(this);
        container.addView(gameView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.resume();   // start the game thread
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.pause();    // stop the game thread safely
    }

    @Override
    public void onBackPressed() {
        // Back button: go to the home screen first, then exit on the second press
        if (gameView.goBackToHome()) {
            return;
        }
        super.onBackPressed();
    }
}