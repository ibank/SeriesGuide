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

import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.battlelancer.seriesguide.Constants.ShowSorting;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.ui.dialogs.CheckInDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.ConfirmDeleteDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.ListsDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.SortDialogFragment;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.FlagTask.FlagAction;
import com.battlelancer.seriesguide.util.FlagTask.OnFlagListener;
import com.battlelancer.seriesguide.util.ImageProvider;
import com.battlelancer.seriesguide.util.ShareUtils;
import com.battlelancer.seriesguide.util.TaskManager;
import com.battlelancer.seriesguide.util.Utils;
import com.battlelancer.seriesguide.x.R;
import com.google.analytics.tracking.android.EasyTracker;
import com.uwetrottmann.androidutils.AndroidUtils;

/**
 * Displays the list of shows in a users local library.
 * 
 * @author Uwe Trottmann
 */
public class ShowsFragment extends SherlockFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, OnItemClickListener, OnFlagListener {

    private static final String TAG = "ShowsFragment";

    public static final int LOADER_ID = R.layout.shows_fragment;

    public static final String FILTER_ID = "filterid";

    // context menu items
    private static final int CONTEXT_DELETE_ID = 200;

    private static final int CONTEXT_UPDATE_ID = 201;

    private static final int CONTEXT_FLAG_NEXT_ID = 202;

    private static final int CONTEXT_FAVORITE_ID = 203;

    private static final int CONTEXT_UNFAVORITE_ID = 204;

    private static final int CONTEXT_HIDE_ID = 205;

    private static final int CONTEXT_UNHIDE_ID = 206;

    private static final int CONTEXT_MANAGE_LISTS_ID = 207;

    private static final int CONTEXT_CHECKIN_ID = 208;

    // Show Filter Ids
    private static final int SHOWFILTER_ALL = 0;

    private static final int SHOWFILTER_FAVORITES = 1;

    private static final int SHOWFILTER_UNSEENEPISODES = 2;

    private static final int SHOWFILTER_HIDDEN = 3;

    private SlowAdapter mAdapter;

    private GridView mGrid;

    private ShowSorting mSorting;

    public static ShowsFragment newInstance() {
        ShowsFragment f = new ShowsFragment();
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.shows_fragment, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getActivity());

        // get settings
        updateSorting(prefs);

        mAdapter = new SlowAdapter(getActivity(), null, 0);

        // setup grid view
        mGrid = (GridView) getView().findViewById(R.id.showlist);
        mGrid.setAdapter(mAdapter);
        mGrid.setOnItemClickListener(this);
        View emptyView = getView().findViewById(R.id.empty);
        if (emptyView != null) {
            mGrid.setEmptyView(emptyView);
        }
        registerForContextMenu(mGrid);

        // start loading data, use saved show filter
        int showfilter = prefs.getInt(SeriesGuidePreferences.KEY_SHOWFILTER, 0);
        Bundle args = new Bundle();
        args.putInt(FILTER_ID, showfilter);
        getLoaderManager().initLoader(LOADER_ID, args, this);

        // listen for some settings changes
        prefs.registerOnSharedPreferenceChangeListener(mPrefsListener);

        setHasOptionsMenu(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        prefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        menuInfo.toString();

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        final Cursor show = getActivity().getContentResolver().query(
                Shows.buildShowUri(String.valueOf(info.id)), new String[] {
                        Shows.FAVORITE, Shows.HIDDEN
                }, null, null, null);
        show.moveToFirst();
        if (show.getInt(0) == 0) {
            menu.add(0, CONTEXT_FAVORITE_ID, 2, R.string.context_favorite);
        } else {
            menu.add(0, CONTEXT_UNFAVORITE_ID, 2, R.string.context_unfavorite);
        }
        if (show.getInt(1) == 0) {
            menu.add(0, CONTEXT_HIDE_ID, 3, R.string.context_hide);
        } else {
            menu.add(0, CONTEXT_UNHIDE_ID, 3, R.string.context_unhide);
        }
        show.close();

        menu.add(0, CONTEXT_CHECKIN_ID, 0, R.string.checkin);
        menu.add(0, CONTEXT_FLAG_NEXT_ID, 1, R.string.context_marknext);
        menu.add(0, CONTEXT_MANAGE_LISTS_ID, 4, R.string.list_item_manage);
        menu.add(0, CONTEXT_UPDATE_ID, 5, R.string.context_updateshow);
        menu.add(0, CONTEXT_DELETE_ID, 6, R.string.delete_show);
    }

    @Override
    public boolean onContextItemSelected(android.view.MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();

        switch (item.getItemId()) {
            case CONTEXT_CHECKIN_ID: {
                Cursor show = (Cursor) mAdapter.getItem(info.position);
                final String episodeId = show.getString(ShowsQuery.NEXTEPISODE);
                if (TextUtils.isEmpty(episodeId)) {
                    return true;
                }

                // look up episode
                final Cursor episode = getActivity().getContentResolver().query(
                        Episodes.buildEpisodeUri(episodeId), new String[] {
                                Episodes.SEASON, Episodes.NUMBER, Episodes.TITLE
                        }, null, null, null);
                if (episode != null && episode.moveToFirst()) {
                    final String episodeString = ShareUtils.onCreateShareString(
                            getActivity(), episode);

                    // display a check-in dialog
                    CheckInDialogFragment f = CheckInDialogFragment.newInstance(
                            show.getString(ShowsQuery.IMDB_ID), (int) info.id, episode.getInt(0),
                            episode.getInt(1), episodeString);
                    f.show(getFragmentManager(), "checkin-dialog");

                }
                if (episode != null) {
                    episode.close();
                }
                return true;
            }
            case CONTEXT_FAVORITE_ID: {
                fireTrackerEvent("Favorite show");

                ContentValues values = new ContentValues();
                values.put(Shows.FAVORITE, true);
                getActivity().getContentResolver().update(
                        Shows.buildShowUri(String.valueOf(info.id)), values, null, null);

                Utils.runNotificationService(getActivity());

                Toast.makeText(getActivity(), getString(R.string.favorited), Toast.LENGTH_SHORT)
                        .show();
                return true;
            }
            case CONTEXT_UNFAVORITE_ID: {
                fireTrackerEvent("Unfavorite show");

                ContentValues values = new ContentValues();
                values.put(Shows.FAVORITE, false);
                getActivity().getContentResolver().update(
                        Shows.buildShowUri(String.valueOf(info.id)), values, null, null);
                Toast.makeText(getActivity(), getString(R.string.unfavorited), Toast.LENGTH_SHORT)
                        .show();
                return true;
            }
            case CONTEXT_HIDE_ID: {
                fireTrackerEvent("Hidden show");

                ContentValues values = new ContentValues();
                values.put(Shows.HIDDEN, true);
                getActivity().getContentResolver().update(
                        Shows.buildShowUri(String.valueOf(info.id)), values, null, null);
                Toast.makeText(getActivity(), getString(R.string.hidden), Toast.LENGTH_SHORT)
                        .show();
                return true;
            }
            case CONTEXT_UNHIDE_ID: {
                fireTrackerEvent("Unhidden show");

                ContentValues values = new ContentValues();
                values.put(Shows.HIDDEN, false);
                getActivity().getContentResolver().update(
                        Shows.buildShowUri(String.valueOf(info.id)), values, null, null);
                Toast.makeText(getActivity(), getString(R.string.unhidden), Toast.LENGTH_SHORT)
                        .show();
                return true;
            }
            case CONTEXT_DELETE_ID:
                fireTrackerEvent("Delete show");

                if (!TaskManager.getInstance(getActivity()).isUpdateTaskRunning(true)) {
                    showDeleteDialog(info.id);
                }
                return true;
            case CONTEXT_UPDATE_ID:
                fireTrackerEvent("Update show");

                ((ShowsActivity) getActivity()).performUpdateTask(false, String.valueOf(info.id));
                return true;
            case CONTEXT_FLAG_NEXT_ID:
                fireTrackerEvent("Mark next episode");

                Cursor show = (Cursor) mAdapter.getItem(info.position);
                DBUtils.markNextEpisode(getActivity(), this, (int) info.id,
                        show.getInt(ShowsQuery.NEXTEPISODE));

                return true;
            case CONTEXT_MANAGE_LISTS_ID: {
                ListsDialogFragment.showListsDialog(String.valueOf(info.id), 1,
                        getFragmentManager());
                return true;
            }
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.shows_menu, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            final CharSequence[] items = getResources().getStringArray(R.array.shsorting);
            menu.findItem(R.id.menu_showsortby).setTitle(
                    getString(R.string.sort) + ": " + items[mSorting.index()]);
        }
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.menu_showsortby: {
                fireTrackerEvent("Sort shows");

                showSortDialog();
                return true;
            }
            default: {
                return super.onOptionsItemSelected(item);
            }
        }
    }

    @TargetApi(16)
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // display overview for this show

        Intent i = new Intent(getActivity(), OverviewActivity.class);
        i.putExtra(OverviewFragment.InitBundle.SHOW_TVDBID, (int) id);
        if (AndroidUtils.isJellyBeanOrHigher()) {
            Bundle options = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.getWidth(),
                    view.getHeight()).toBundle();
            getActivity().startActivity(i, options);
        } else {
            startActivity(i);
        }
    }

    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String selection = null;
        String[] selectionArgs = null;

        int filterId = args.getInt(FILTER_ID);
        switch (filterId) {
            case SHOWFILTER_ALL:
                selection = Shows.HIDDEN + "=?";
                selectionArgs = new String[] {
                        "0"
                };
                break;
            case SHOWFILTER_FAVORITES:
                selection = Shows.FAVORITE + "=? AND " + Shows.HIDDEN + "=?";
                selectionArgs = new String[] {
                        "1", "0"
                };
                break;
            case SHOWFILTER_UNSEENEPISODES:
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getActivity());
                int upcomingLimit = Integer.valueOf(prefs.getString(
                        SeriesGuidePreferences.KEY_UPCOMING_LIMIT, "1"));

                selection = Shows.NEXTAIRDATEMS + "!=? AND " + Shows.NEXTAIRDATEMS + " <=? AND "
                        + Shows.HIDDEN + "=?";
                // Display shows upcoming within x amount of days + 1 hour
                String inTheFuture = String.valueOf(System.currentTimeMillis() + upcomingLimit
                        * DateUtils.DAY_IN_MILLIS + DateUtils.HOUR_IN_MILLIS);
                selectionArgs = new String[] {
                        DBUtils.UNKNOWN_NEXT_AIR_DATE, inTheFuture, "0"
                };
                break;
            case SHOWFILTER_HIDDEN:
                selection = Shows.HIDDEN + "=?";
                selectionArgs = new String[] {
                        "1"
                };
                break;
        }

        return new CursorLoader(getActivity(), Shows.CONTENT_URI, ShowsQuery.PROJECTION, selection,
                selectionArgs, mSorting.query());
    }

    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        // Swap the new cursor in. (The framework will take care of closing the
        // old cursor once we return.)
        mAdapter.swapCursor(data);
    }

    public void onLoaderReset(Loader<Cursor> arg0) {
        // This is called when the last Cursor provided to onLoadFinished()
        // above is about to be closed. We need to make sure we are no
        // longer using it.
        mAdapter.swapCursor(null);
    }

    private class SlowAdapter extends CursorAdapter {

        private LayoutInflater mLayoutInflater;

        private static final int LAYOUT = R.layout.shows_row;

        public SlowAdapter(Context context, Cursor c, int flags) {
            super(context, c, flags);
            mLayoutInflater = (LayoutInflater) context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
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
                convertView = mLayoutInflater.inflate(LAYOUT, null);

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
            viewHolder.name.setText(mCursor.getString(ShowsQuery.TITLE));
            viewHolder.network.setText(mCursor.getString(ShowsQuery.NETWORK));

            final boolean isFavorited = mCursor.getInt(ShowsQuery.FAVORITE) == 1;
            viewHolder.favorited.setVisibility(isFavorited ? View.VISIBLE : View.GONE);

            // next episode info
            String fieldValue = mCursor.getString(ShowsQuery.NEXTTEXT);
            if (fieldValue.length() == 0) {
                // show show status if there are currently no more
                // episodes
                int status = mCursor.getInt(ShowsQuery.STATUS);

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
                fieldValue = mCursor.getString(ShowsQuery.NEXTAIRDATETEXT);
                viewHolder.episodeTime.setText(fieldValue);
            }

            // airday
            final String[] values = Utils.parseMillisecondsToTime(
                    mCursor.getLong(ShowsQuery.AIRSTIME),
                    mCursor.getString(ShowsQuery.AIRSDAYOFWEEK), mContext);
            if (getResources().getBoolean(R.bool.isLargeTablet)) {
                viewHolder.airsTime.setText("/ " + values[1] + " " + values[0]);
            } else {
                viewHolder.airsTime.setText(values[1] + " " + values[0]);
            }

            // set poster
            final String imagePath = mCursor.getString(ShowsQuery.POSTER);
            ImageProvider.getInstance(mContext).loadPosterThumb(viewHolder.poster, imagePath);

            return convertView;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            // do nothing here
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            return mLayoutInflater.inflate(LAYOUT, parent, false);
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

    private interface ShowsQuery {

        String[] PROJECTION = {
                BaseColumns._ID, Shows.TITLE, Shows.NEXTTEXT, Shows.AIRSTIME, Shows.NETWORK,
                Shows.POSTER, Shows.AIRSDAYOFWEEK, Shows.STATUS, Shows.NEXTAIRDATETEXT,
                Shows.FAVORITE, Shows.NEXTEPISODE, Shows.IMDBID
        };

        // int _ID = 0;

        int TITLE = 1;

        int NEXTTEXT = 2;

        int AIRSTIME = 3;

        int NETWORK = 4;

        int POSTER = 5;

        int AIRSDAYOFWEEK = 6;

        int STATUS = 7;

        int NEXTAIRDATETEXT = 8;

        int FAVORITE = 9;

        int NEXTEPISODE = 10;

        int IMDB_ID = 11;
    }

    public void fireTrackerEvent(String label) {
        EasyTracker.getTracker().trackEvent(TAG, "Click", label, (long) 0);
    }

    private void showDeleteDialog(long showId) {
        FragmentManager fm = getFragmentManager();
        ConfirmDeleteDialogFragment deleteDialog = ConfirmDeleteDialogFragment.newInstance(String
                .valueOf(showId));
        deleteDialog.show(fm, "fragment_delete");
    }

    private void showSortDialog() {
        FragmentManager fm = getFragmentManager();
        SortDialogFragment sortDialog = SortDialogFragment.newInstance(R.array.shsorting,
                R.array.shsortingData, mSorting.index(),
                SeriesGuidePreferences.KEY_SHOW_SORT_ORDER, R.string.pref_showsorting);
        sortDialog.show(fm, "fragment_sort");
    }

    /**
     * Fetches the sorting preference and stores it in {@code mSorting}.
     * 
     * @param prefs
     * @return Returns true if the value changed, false otherwise.
     */
    private boolean updateSorting(SharedPreferences prefs) {
        final ShowSorting oldSorting = mSorting;

        mSorting = ShowSorting.fromValue(prefs.getString(
                SeriesGuidePreferences.KEY_SHOW_SORT_ORDER, ShowSorting.FAVORITES_FIRST.value()));

        if (oldSorting != mSorting) {
            return true;
        } else {
            return false;
        }
    }

    private final OnSharedPreferenceChangeListener mPrefsListener = new OnSharedPreferenceChangeListener() {

        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            boolean isAffectingChange = false;

            if (key.equals(SeriesGuidePreferences.KEY_SHOW_SORT_ORDER)) {
                updateSorting(sharedPreferences);
                isAffectingChange = true;
            } else if (key.equals(SeriesGuidePreferences.KEY_UPCOMING_LIMIT)) {
                isAffectingChange = true;
            }

            if (isAffectingChange) {
                onFilterChanged(getSherlockActivity().getSupportActionBar()
                        .getSelectedNavigationIndex());
            }
        }
    };

    @Override
    public void onFlagCompleted(FlagAction action, int showId, int itemId, boolean isSuccessful) {
        if (isSuccessful && isAdded()) {
            Utils.updateLatestEpisode(getActivity(), String.valueOf(showId));
        }
    }

    public void onFilterChanged(int itemPosition) {
        // requery with the new filter
        Bundle args = new Bundle();
        args.putInt(ShowsFragment.FILTER_ID, itemPosition);
        getLoaderManager().restartLoader(ShowsFragment.LOADER_ID, args, this);
    }

}
