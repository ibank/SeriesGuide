/*
 * Copyright 2012 Uwe Trottmann
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
 * 
 */

package com.battlelancer.seriesguide.ui.dialogs;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.battlelancer.seriesguide.ui.SeriesGuidePreferences;
import com.battlelancer.seriesguide.x.R;

public class ChangesDialogFragment extends DialogFragment {
    private static final String MARKETLINK_HTTP = "http://play.google.com/store/apps/details?id=com.battlelancer.seriesguide";

    private static final String MARKETLINK_APP = "market://details?id=com.battlelancer.seriesguide";

    public static final String TAG = "ChangesDialogFragment";

    public static ChangesDialogFragment show(FragmentManager fm) {
        ChangesDialogFragment f = new ChangesDialogFragment();
        FragmentTransaction ft = fm.beginTransaction();
        f.show(ft, TAG);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // hide title, use custom theme
        if (SeriesGuidePreferences.THEME == R.style.ICSBaseTheme) {
            setStyle(STYLE_NO_TITLE, 0);
        } else {
            setStyle(STYLE_NO_TITLE, R.style.SeriesGuideTheme_Dialog);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View layout = inflater.inflate(R.layout.changes_dialog, null);

        // title
        ((TextView) layout.findViewById(R.id.title)).setText(getString(R.string.app_name));

        // message
        TextView message = (TextView) layout.findViewById(R.id.message);
        message.setMovementMethod(LinkMovementMethod.getInstance());

        // buttons
        Button buttonNegative = (Button) layout.findViewById(R.id.buttonNegative);
        buttonNegative.setText(R.string.download_stable);
        buttonNegative.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent myIntent = new Intent(Intent.ACTION_VIEW, Uri
                            .parse(MARKETLINK_APP));
                    startActivity(myIntent);
                } catch (ActivityNotFoundException e) {
                    Intent myIntent = new Intent(Intent.ACTION_VIEW, Uri
                            .parse(MARKETLINK_HTTP));
                    startActivity(myIntent);
                }
            }
        });
        Button buttonPositive = (Button) layout.findViewById(R.id.buttonPositive);
        buttonPositive.setText(R.string.gobreak);
        buttonPositive.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        return layout;
    }
}
