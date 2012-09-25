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

package com.battlelancer.seriesguide.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.LoaderManager.LoaderCallbacks;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.actionbarsherlock.app.ActionBar;
import com.battlelancer.seriesguide.Constants.ShowSorting;
import com.battlelancer.seriesguide.provider.SeriesContract;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.ui.dialogs.CheckInDialogFragment;
import com.battlelancer.seriesguide.util.ImageProvider;
import com.battlelancer.seriesguide.util.ShareUtils;
import com.battlelancer.seriesguide.util.Utils;
import com.battlelancer.seriesguide.x.R;
import com.google.analytics.tracking.android.EasyTracker;

/**
 * Displays a searchable list of shows to allow quickly checking into a shows
 * next episode.
 */
public class CheckinActivity extends BaseActivity implements LoaderCallbacks<Cursor> {

    private static final int LOADER_ID = R.layout.checkin;

    private EditText mSearchBox;

    private SimpleCursorAdapter mAdapter;

    private String mSearchFilter;

    @Override
    protected void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);

        setContentView(R.layout.checkin);

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setTitle(getString(R.string.checkin));
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);

        // setup search box
        mSearchBox = (EditText) findViewById(R.id.editTextCheckinSearch);
        mSearchBox.addTextChangedListener(new TextWatcher() {

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mSearchFilter = !TextUtils.isEmpty(s) ? s.toString() : null;
                getSupportLoaderManager().restartLoader(LOADER_ID, null, CheckinActivity.this);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // setup clear button
        findViewById(R.id.imageButtonClearSearch).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mSearchBox.setText(null);
            }
        });

        // setup adapter
        String[] from = new String[] {
                SeriesContract.Shows.TITLE, SeriesContract.Shows.NEXTTEXT,
                SeriesContract.Shows.AIRSTIME, SeriesContract.Shows.NETWORK,
                SeriesContract.Shows.POSTER
        };
        int[] to = new int[] {
                R.id.seriesname, R.id.TextViewShowListNextEpisode, R.id.TextViewShowListAirtime,
                R.id.TextViewShowListNetwork, R.id.showposter
        };
        int layout = R.layout.shows_row;
        mAdapter = new SlowAdapter(this, layout, null, from, to, 0);

        // setup grid view
        GridView list = (GridView) findViewById(R.id.gridViewCheckinShows);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                Cursor show = (Cursor) mAdapter.getItem(position);
                final String episodeId = show.getString(CheckinQuery.NEXTEPISODE);
                if (TextUtils.isEmpty(episodeId)) {
                    return;
                }

                // look up episode
                final Cursor episode = getContentResolver().query(
                        Episodes.buildEpisodeUri(episodeId), new String[] {
                                Episodes.SEASON, Episodes.NUMBER, Episodes.TITLE
                        }, null, null, null);
                if (episode != null && episode.moveToFirst()) {
                    final String episodeString = ShareUtils.onCreateShareString(
                            CheckinActivity.this, episode);

                    // display a check-in dialog
                    CheckInDialogFragment f = CheckInDialogFragment.newInstance(
                            show.getString(CheckinQuery.IMDBID), (int) id, episode.getInt(0),
                            episode.getInt(1), episodeString);
                    f.show(getSupportFragmentManager(), "checkin-dialog");

                    episode.close();
                }
            }
        });
        list.setEmptyView(findViewById(R.id.empty));

        getSupportLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EasyTracker.getInstance().activityStart(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        EasyTracker.getInstance().activityStop(this);
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        Uri baseUri;
        if (mSearchFilter != null) {
            baseUri = Uri.withAppendedPath(Shows.CONTENT_FILTER_URI, Uri.encode(mSearchFilter));
        } else {
            baseUri = Shows.CONTENT_URI;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String fakeInAnHour = String.valueOf(Utils.getFakeCurrentTime(prefs)
                + DateUtils.HOUR_IN_MILLIS);

        return new CursorLoader(this, baseUri, CheckinQuery.PROJECTION, CheckinQuery.SELECTION,
                new String[] {
                        fakeInAnHour
                }, ShowSorting.UPCOMING.query());
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> arg0) {
        mAdapter.swapCursor(null);
    }

    private class SlowAdapter extends SimpleCursorAdapter {

        private LayoutInflater mLayoutInflater;

        private int mLayout;

        public SlowAdapter(Context context, int layout, Cursor c, String[] from, int[] to, int flags) {
            super(context, layout, c, from, to, flags);
            mLayoutInflater = (LayoutInflater) context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayout = layout;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (!mDataValid) {
                throw new IllegalStateException(
                        "this should only be called when the cursor is valid");
            }
            if (!mCursor.moveToPosition(position)) {
                throw new IllegalStateException("couldn't move cursor to position " + position);
            }

            final ViewHolder viewHolder;

            if (convertView == null) {
                convertView = mLayoutInflater.inflate(mLayout, null);

                viewHolder = new ViewHolder();
                viewHolder.name = (TextView) convertView.findViewById(R.id.seriesname);
                viewHolder.network = (TextView) convertView
                        .findViewById(R.id.TextViewShowListNetwork);
                viewHolder.episode = (TextView) convertView
                        .findViewById(R.id.TextViewShowListNextEpisode);
                viewHolder.episodeTime = (TextView) convertView.findViewById(R.id.episodetime);
                viewHolder.airsTime = (TextView) convertView
                        .findViewById(R.id.TextViewShowListAirtime);
                viewHolder.poster = (ImageView) convertView.findViewById(R.id.showposter);
                viewHolder.favorited = convertView.findViewById(R.id.favoritedLabel);
                viewHolder.collected = convertView.findViewById(R.id.collectedLabel);

                convertView.setTag(viewHolder);
            } else {
                viewHolder = (ViewHolder) convertView.getTag();
            }

            // set text properties immediately
            viewHolder.name.setText(mCursor.getString(CheckinQuery.TITLE));
            viewHolder.network.setText(mCursor.getString(CheckinQuery.NETWORK));

            final boolean isFavorited = mCursor.getInt(CheckinQuery.FAVORITE) == 1;
            viewHolder.favorited.setVisibility(isFavorited ? View.VISIBLE : View.GONE);

            // next episode info
            String fieldValue = mCursor.getString(CheckinQuery.NEXTTEXT);
            if (fieldValue.length() == 0) {
                // show show status if there are currently no more
                // episodes
                int status = mCursor.getInt(CheckinQuery.STATUS);

                // Continuing == 1 and Ended == 0
                if (status == 1) {
                    viewHolder.episodeTime.setText(getString(R.string.show_isalive));
                } else if (status == 0) {
                    viewHolder.episodeTime.setText(getString(R.string.show_isnotalive));
                } else {
                    viewHolder.episodeTime.setText("");
                }
                viewHolder.episode.setText("");
            } else {
                viewHolder.episode.setText(fieldValue);
                fieldValue = mCursor.getString(CheckinQuery.NEXTAIRDATETEXT);
                viewHolder.episodeTime.setText(fieldValue);
            }

            // airday
            final String[] values = Utils.parseMillisecondsToTime(
                    mCursor.getLong(CheckinQuery.AIRSTIME),
                    mCursor.getString(CheckinQuery.AIRSDAYOFWEEK), mContext);
            viewHolder.airsTime.setText(values[1] + " " + values[0]);

            // set poster
            final String imagePath = mCursor.getString(CheckinQuery.POSTER);
            ImageProvider.getInstance(mContext).loadPosterThumb(viewHolder.poster, imagePath);

            return convertView;
        }
    }

    static class ViewHolder {

        public TextView name;

        public TextView network;

        public TextView episode;

        public TextView episodeTime;

        public TextView airsTime;

        public ImageView poster;

        public View favorited;

        public View collected;
    }

    interface CheckinQuery {
        String[] PROJECTION = {
                Shows._ID, Shows.TITLE, Shows.NEXTTEXT, Shows.AIRSTIME, Shows.NETWORK,
                Shows.POSTER, Shows.AIRSDAYOFWEEK, Shows.STATUS, Shows.NEXTAIRDATETEXT,
                Shows.FAVORITE, Shows.IMDBID, Shows.NEXTEPISODE, Shows.HIDDEN, Shows.NEXTAIRDATEMS
        };

        String SELECTION = Shows.NEXTEPISODE + "!='' AND " + Shows.HIDDEN + "=0 AND "
                + Shows.NEXTAIRDATEMS + "<?";

        int _ID = 0;

        int TITLE = 1;

        int NEXTTEXT = 2;

        int AIRSTIME = 3;

        int NETWORK = 4;

        int POSTER = 5;

        int AIRSDAYOFWEEK = 6;

        int STATUS = 7;

        int NEXTAIRDATETEXT = 8;

        int FAVORITE = 9;

        int IMDBID = 10;

        int NEXTEPISODE = 11;
    }
}
