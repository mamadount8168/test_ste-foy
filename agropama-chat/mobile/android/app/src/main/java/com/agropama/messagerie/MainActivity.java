package com.agropama.messagerie;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int AGROPAMA_GREEN = Color.parseColor("#1F6F3F");

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Depuis Android 15, l'application est dessinée sous la barre d'état et la barre de navigation.
        // On garde le contenu dans la zone visible (et au-dessus du clavier) en ajoutant des marges.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Barres transparentes : le fond vert d'Agropama apparaît derrière (Android 14 et moins).
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);

        View webView = getBridge().getWebView();
        final ViewGroup container = (ViewGroup) webView.getParent();
        container.setBackgroundColor(AGROPAMA_GREEN);

        ViewCompat.setOnApplyWindowInsetsListener(container, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(container);
    }
}
