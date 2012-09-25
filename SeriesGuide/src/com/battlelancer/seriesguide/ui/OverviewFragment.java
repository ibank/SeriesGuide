/*
 * Copyright 2011 Uwe Trottmann
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

package com.battlelancer.seriesguide.ui;

import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentTransaction;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.battlelancer.seriesguide.Constants;
import com.battlelancer.seriesguide.items.Series;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Seasons;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.ui.dialogs.CheckInDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.ListsDialogFragment;
import com.battlelancer.seriesguide.util.DBUtils;
import com.battlelancer.seriesguide.util.FetchArtTask;
import com.battlelancer.seriesguide.util.FlagTask;
import com.battlelancer.seriesguide.util.FlagTask.FlagAction;
import com.battlelancer.seriesguide.util.FlagTask.OnFlagListener;
import com.battlelancer.seriesguide.util.ShareUtils;
import com.battlelancer.seriesguide.util.ShareUtils.ShareItems;
import com.battlelancer.seriesguide.util.ShareUtils.ShareMethod;
import com.battlelancer.seriesguide.util.TraktSummaryTask;
import com.battlelancer.seriesguide.util.TraktTask.OnTraktActionCompleteListener;
import com.battlelancer.seriesguide.util.Utils;
import com.battlelancer.seriesguide.x.R;
import com.google.analytics.tracking.android.EasyTracker;
import com.uwetrottmann.androidutils.AndroidUtils;

/**
 * Displays general information about a show and its next episode. Displays a
 * {@link SeasonsFragment} on larger screens.
 */
public class OverviewFragment extends SherlockFragment implements OnTraktActionCompleteListener,
        OnFlagListener {

    private static final String TAG = "OverviewFragment";

    private boolean mDualPane;

    private Series mShow;

    protected int mEpisodeId;

    private FetchArtTask mArtTask;

    private TraktSummaryTask mTraktTask;

    final private Bundle mShareData = new Bundle();

    private long mAirtime;

    private boolean mCollected;

    private int mSeasonNumber;

    private int mEpisodeNumber;

    private View mSeasonsButton;

    /**
     * All values have to be integer.
     */
    public interface InitBundle {
        String SHOW_TVDBID = "show_tvdbid";
    }

    public void fireTrackerEvent(String label) {
        EasyTracker.getTracker().trackEvent("Overview", "Click", label, (long) 0);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (container == null) {
            return null;
        }

        View v = inflater.inflate(R.layout.overview_fragment, container, false);
        v.findViewById(R.id.showinfo).setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                onShowShowInfo(v);
            }
        });
        mSeasonsButton = v.findViewById(R.id.gotoseasons);
        if (mSeasonsButton != null) {
            mSeasonsButton.setOnClickListener(new OnClickListener() {

                public void onClick(View v) {
                    showSeasons();
                }
            });
        }

        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (getShowId() == 0) {
            getActivity().finish();
            return;
        }

        onLoadShow();

        // Check to see if we have a frame in which to embed the details
        // fragment directly in the containing UI.
        View seasonsFragment = getActivity().findViewById(R.id.fragment_seasons);
        mDualPane = seasonsFragment != null && seasonsFragment.getVisibility() == View.VISIBLE;
        if (mDualPane) {
            showSeasons();
        }

        setHasOptionsMenu(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        onLoadEpisode();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mArtTask != null) {
            mArtTask.cancel(true);
            mArtTask = null;
        }
        if (mTraktTask != null) {
            mTraktTask.cancel(true);
            mTraktTask = null;
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.overview_menu, menu);

        // enable/disable menu items
        boolean isEpisodeVisible;
        if (mEpisodeId != 0) {
            isEpisodeVisible = true;
            menu.findItem(R.id.menu_flag_collected).setIcon(
                    mCollected ? R.drawable.ic_collected : R.drawable.ic_action_collect);
        } else {
            isEpisodeVisible = false;
        }
        menu.findItem(R.id.menu_checkin).setEnabled(isEpisodeVisible);
        menu.findItem(R.id.menu_flag_watched).setEnabled(isEpisodeVisible);
        menu.findItem(R.id.menu_flag_collected).setEnabled(isEpisodeVisible);
        menu.findItem(R.id.menu_calendarevent).setEnabled(isEpisodeVisible);
        menu.findItem(R.id.menu_share).setEnabled(isEpisodeVisible);
        menu.findItem(R.id.menu_rate_trakt).setEnabled(isEpisodeVisible);
        menu.findItem(R.id.menu_manage_lists).setEnabled(isEpisodeVisible);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_checkin: {
                // check in
                CheckInDialogFragment f = CheckInDialogFragment.newInstance(
                        mShow.getImdbId(), getShowId(), mSeasonNumber,
                        mEpisodeNumber, mShareData.getString(ShareItems.EPISODESTRING));
                f.show(getFragmentManager(), "checkin-dialog");

                fireTrackerEvent("Check In");
                return true;
            }
            case R.id.menu_flag_watched: {
                // flag watched
                onFlagWatched();

                fireTrackerEvent("Flag Watched");
                return true;
            }
            case R.id.menu_flag_collected: {
                // toggle collected
                onToggleCollected();
                item.setIcon(mCollected ? R.drawable.ic_collected : R.drawable.ic_action_collect);

                fireTrackerEvent("Toggle Collected");
                return true;
            }
            case R.id.menu_calendarevent: {
                // add calendar event
                ShareUtils.onAddCalendarEvent(getActivity(), mShow.getTitle(),
                        mShareData.getString(ShareItems.EPISODESTRING), mAirtime,
                        mShow.getRuntime());

                fireTrackerEvent("Add to calendar");
                return true;
            }
            case R.id.menu_rate_trakt: {
                // rate episode on trakt.tv
                onShareEpisode(ShareMethod.RATE_TRAKT, true);

                fireTrackerEvent("Rate (trakt)");
                return true;
            }
            case R.id.menu_share: {
                // share episode
                onShareEpisode(ShareMethod.OTHER_SERVICES, true);

                fireTrackerEvent("Share (apps)");
                return true;
            }
            case R.id.menu_manage_lists: {
                ListsDialogFragment.showListsDialog(String.valueOf(mEpisodeId), 3,
                        getFragmentManager());
                return true;
            }
            case R.id.menu_search: {
                // search through this shows episodes
                getActivity().onSearchRequested();

                fireTrackerEvent("Search show episodes");
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void onShareEpisode(ShareMethod shareMethod, boolean isInvalidateOptionsMenu) {
        ShareUtils.onShareEpisode(getActivity(), mShareData, shareMethod, this);

        if (isInvalidateOptionsMenu) {
            // invalidate the options menu so a potentially new
            // quick share action is displayed
            getSherlockActivity().invalidateOptionsMenu();
        }
    }

    private void showSeasons() {
        if (mDualPane) {
            // Check if fragment is shown, create new if needed.
            SeasonsFragment seasons = (SeasonsFragment) getFragmentManager().findFragmentById(
                    R.id.fragment_seasons);
            if (seasons == null) {
                // Make new fragment to show this selection.
                seasons = SeasonsFragment.newInstance(getShowId());

                // Execute a transaction, replacing any existing
                // fragment with this one inside the frame.
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                ft.setCustomAnimations(R.anim.fragment_slide_left_enter,
                        R.anim.fragment_slide_left_exit);
                ft.replace(R.id.fragment_seasons, seasons);
                ft.commit();
            }
        } else {
            // Otherwise we need to launch a new activity to display
            // the dialog fragment with selected text.
            Intent intent = new Intent();
            intent.setClass(getActivity(), SeasonsActivity.class);
            intent.putExtra(SeasonsFragment.InitBundle.SHOW_TVDBID, getShowId());
            startActivity(intent);
            getSherlockActivity().overridePendingTransition(R.anim.fragment_slide_left_enter,
                    R.anim.fragment_slide_left_exit);
        }

    }

    private int getShowId() {
        return getArguments().getInt(InitBundle.SHOW_TVDBID);
    }

    private void onLoadShow() {
        mShow = DBUtils.getShow(getActivity(), String.valueOf(getShowId()));
        if (mShow == null) {
            return;
        }

        // Save info for sharing
        mShareData.putInt(ShareItems.TVDBID, getShowId());

        // Show name
        final TextView showname = (TextView) getActivity().findViewById(R.id.seriesname);
        showname.setText(mShow.getTitle());

        // Running state
        final TextView status = (TextView) getActivity().findViewById(R.id.showStatus);
        if (mShow.getStatus() == 1) {
            status.setTextColor(Color.GREEN);
            status.setText(getString(R.string.show_isalive));
        } else if (mShow.getStatus() == 0) {
            status.setTextColor(Color.GRAY);
            status.setText(getString(R.string.show_isnotalive));
        }

        // poster
        final ImageView background = (ImageView) getView().findViewById(R.id.background);
        Utils.setPosterBackground(background, mShow.getPoster(), getActivity());

        // air time and network
        final StringBuilder timeAndNetwork = new StringBuilder();
        if (mShow.getAirsDayOfWeek().length() != 0 && mShow.getAirsTime() != -1) {
            String[] values = Utils.parseMillisecondsToTime(mShow.getAirsTime(),
                    mShow.getAirsDayOfWeek(), getActivity());
            timeAndNetwork.append(values[1]).append(" ").append(values[0]);
        } else {
            timeAndNetwork.append(getString(R.string.show_noairtime));
        }
        if (mShow.getNetwork().length() != 0) {
            timeAndNetwork.append(" ").append(getString(R.string.show_network)).append(" ")
                    .append(mShow.getNetwork());
        }
        final TextView showmeta = (TextView) getActivity().findViewById(R.id.showmeta);
        showmeta.setText(timeAndNetwork.toString());
    }

    private void getEpisodeData() {
        final Context context = getActivity();
        if (context == null) {
            return;
        }

        final TextView episodeTitle = (TextView) getView().findViewById(R.id.episodeTitle);
        final TextView episodeTime = (TextView) getView().findViewById(R.id.episodeTime);
        final TextView episodeInfo = (TextView) getView().findViewById(R.id.episodeInfo);
        final View episodemeta = getView().findViewById(R.id.episodemeta);

        String episodestring = "";

        if (mEpisodeId != 0) {
            final Cursor episode = context.getContentResolver().query(
                    Episodes.buildEpisodeUri(String.valueOf(mEpisodeId)), EpisodeQuery.PROJECTION,
                    null, null, null);
            if (episode == null || !episode.moveToFirst()) {
                return;
            }

            // some episode properties
            mSeasonNumber = episode.getInt(EpisodeQuery.SEASON);
            mEpisodeNumber = episode.getInt(EpisodeQuery.NUMBER);
            mCollected = episode.getInt(EpisodeQuery.COLLECTED) == 1 ? true : false;
            final String title = episode.getString(EpisodeQuery.TITLE);

            // build share data
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            episodestring = Utils.getNextEpisodeString(prefs, mSeasonNumber, mEpisodeNumber, title);
            mShareData.putInt(ShareItems.SEASON, mSeasonNumber);
            mShareData.putInt(ShareItems.EPISODE, mEpisodeNumber);

            // title
            episodeTitle.setText(title);
            episodeTitle.setVisibility(View.VISIBLE);

            // number
            StringBuilder infoText = new StringBuilder();
            infoText.append(getString(R.string.season)).append(" ").append(mSeasonNumber);
            infoText.append(" ");
            infoText.append(getString(R.string.episode)).append(" ")
                    .append(mEpisodeNumber);
            episodeInfo.setText(infoText);
            episodeInfo.setVisibility(View.VISIBLE);

            // air date
            mAirtime = episode.getLong(EpisodeQuery.FIRSTAIREDMS);
            if (mAirtime != -1) {
                final String[] dayAndTime = Utils.formatToTimeAndDay(mAirtime, context);
                episodeTime.setText(new StringBuilder().append(dayAndTime[2]).append(" (")
                        .append(dayAndTime[1])
                        .append(")"));
                episodeTime.setVisibility(View.VISIBLE);
            }

            // load all other info
            onLoadEpisodeDetails(episode, prefs);
            episodemeta.setVisibility(View.VISIBLE);

            episode.close();
        } else {
            // no next episode: display single line info text, remove other
            // views
            episodeTitle.setText(R.string.no_nextepisode);
            episodeTime.setVisibility(View.GONE);
            episodeInfo.setVisibility(View.GONE);
            episodemeta.setVisibility(View.GONE);

            mAirtime = -1;
            mSeasonNumber = -1;
            mEpisodeNumber = -1;

        }

        // enable/disable applicable menu items
        getSherlockActivity().invalidateOptionsMenu();

        // animate view into visibility
        final View overviewContainer = getView().findViewById(R.id.overview_container);
        if (overviewContainer.getVisibility() == View.GONE) {
            final View progressContainer = getView().findViewById(R.id.progress_container);
            progressContainer.startAnimation(AnimationUtils.loadAnimation(context,
                    android.R.anim.fade_out));
            overviewContainer.startAnimation(AnimationUtils.loadAnimation(context,
                    android.R.anim.fade_in));
            progressContainer.setVisibility(View.GONE);
            overviewContainer.setVisibility(View.VISIBLE);
        }

        // build share string
        final StringBuilder shareString = new StringBuilder(getString(R.string.share_checkout));
        shareString.append(" \"").append(mShow.getTitle());
        shareString.append(" - ").append(episodestring).append("\"");
        mShareData.putString(ShareItems.SHARESTRING, shareString.toString());
        mShareData.putString(ShareItems.EPISODESTRING, episodestring);
    }

    protected void onLoadEpisode() {
        AndroidUtils.executeAsyncTask(new AsyncTask<Void, Void, Integer>() {

            private final static int SUCCESS = 1;

            private final static int ABORT = -1;

            private SherlockFragmentActivity activity;

            @Override
            protected void onPreExecute() {
                activity = getSherlockActivity();
            }

            @Override
            protected Integer doInBackground(Void... params) {
                if (activity == null) {
                    return ABORT;
                }

                mEpisodeId = (int) DBUtils.updateLatestEpisode(activity,
                        String.valueOf(getShowId()));

                return SUCCESS;
            }

            @Override
            protected void onPostExecute(Integer result) {
                if (result == SUCCESS && isAdded()) {
                    activity.invalidateOptionsMenu();
                    getEpisodeData();
                }
            }

        }, new Void[] {});
    }

    @TargetApi(11)
    protected void onLoadEpisodeDetails(final Cursor episode, SharedPreferences prefs) {
        // Description, DVD episode number, Directors, Writers
        ((TextView) getView().findViewById(R.id.TextViewEpisodeDescription)).setText(episode
                .getString(EpisodeQuery.OVERVIEW));
        Utils.setValueOrPlaceholder(getView().findViewById(R.id.textViewEpisodeDVDnumber),
                episode.getString(EpisodeQuery.DVDNUMBER));
        Utils.setValueOrPlaceholder(getView().findViewById(R.id.TextViewEpisodeDirectors),
                Utils.splitAndKitTVDBStrings(episode.getString(EpisodeQuery.DIRECTORS)));
        Utils.setValueOrPlaceholder(getView().findViewById(R.id.TextViewEpisodeWriters),
                Utils.splitAndKitTVDBStrings(episode.getString(EpisodeQuery.WRITERS)));

        // Guest stars
        // don't display an unknown string if there are no guest stars, because
        // then there are none
        ((TextView) getView().findViewById(R.id.TextViewEpisodeGuestStars)).setText(Utils
                .splitAndKitTVDBStrings(episode.getString(EpisodeQuery.GUESTSTARS)));

        // TVDb rating
        final String ratingText = episode.getString(EpisodeQuery.RATING);
        if (ratingText != null && ratingText.length() != 0) {
            ((RatingBar) getView().findViewById(R.id.bar)).setProgress((int) (Double
                    .valueOf(ratingText) / 0.1));
            ((TextView) getView().findViewById(R.id.value)).setText(ratingText + "/10");
        }

        // IMDb button
        String imdbId = episode.getString(EpisodeQuery.IMDBID);
        if (TextUtils.isEmpty(imdbId)) {
            // fall back to show IMDb id
            imdbId = mShow.getImdbId();
        }
        Utils.setUpImdbButton(imdbId, getView().findViewById(R.id.buttonShowInfoIMDB), TAG,
                getActivity());
        mShareData.putString(ShareItems.IMDBID, imdbId);

        // TVDb button
        final String seasonId = episode.getString(EpisodeQuery.REF_SEASON_ID);
        getView().findViewById(R.id.buttonTVDB).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.TVDB_EPISODE_URL_1
                        + getShowId() + Constants.TVDB_EPISODE_URL_2 + seasonId
                        + Constants.TVDB_EPISODE_URL_3 + mEpisodeId));
                startActivity(i);
            }
        });

        // trakt shouts button
        final String episodeTitle = episode.getString(EpisodeQuery.TITLE);
        getView().findViewById(R.id.buttonShouts).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mDualPane) {
                    Intent i = new Intent(getActivity(), TraktShoutsActivity.class);
                    i.putExtras(TraktShoutsActivity.createInitBundle(getShowId(), mSeasonNumber,
                            mEpisodeNumber, episodeTitle));
                    startActivity(i);
                } else {
                    TraktShoutsFragment newFragment = TraktShoutsFragment.newInstance(episodeTitle,
                            getShowId(), mSeasonNumber, mEpisodeNumber);
                    newFragment.show(getFragmentManager(), "shouts-dialog");
                }
            }
        });

        // episode image
        onLoadImage(episode.getString(EpisodeQuery.IMAGE));

        // trakt ratings
        mTraktTask = new TraktSummaryTask(getSherlockActivity(), getView()).episode(getShowId(),
                mSeasonNumber, mEpisodeNumber);
        AndroidUtils.executeAsyncTask(mTraktTask, new Void[] {
                null
        });

        // remaining episodes counter
        if (!mDualPane) {
            final View remainingCount = getView().findViewById(R.id.textViewRemaining);
            if (remainingCount != null) {
                TextView remaining = (TextView) remainingCount;
                remaining.setText(DBUtils.getUnwatchedEpisodesOfShow(getActivity(), mShow.getId(),
                        prefs));
            }
        }
    }

    protected void onLoadImage(String imagePath) {
        final FrameLayout container = (FrameLayout) getActivity().findViewById(R.id.imageContainer);

        // clean up a previous task
        if (mArtTask != null) {
            mArtTask.cancel(true);
            mArtTask = null;
        }
        mArtTask = (FetchArtTask) new FetchArtTask(imagePath, container, getActivity());
        AndroidUtils.executeAsyncTask(mArtTask, new Void[] {
                null
        });
    }

    private void onFlagWatched() {
        new FlagTask(getActivity(), getShowId(), this)
                .episodeWatched(mSeasonNumber, mEpisodeNumber).setItemId(mEpisodeId).setFlag(true)
                .execute();
    }

    private void onToggleCollected() {
        mCollected = !mCollected;
        new FlagTask(getActivity(), getShowId(), null)
                .episodeCollected(mSeasonNumber, mEpisodeNumber).setItemId(mEpisodeId)
                .setFlag(mCollected).execute();
    }

    private void onUpdateSeasons() {
        SeasonsFragment seasons = (SeasonsFragment) getFragmentManager().findFragmentById(
                R.id.fragment_seasons);
        if (seasons != null) {
            seasons.updateUnwatchedCounts(false);
        }
    }

    /**
     * Launch show info activity.
     */
    @TargetApi(16)
    private void onShowShowInfo(View sourceView) {
        Intent i = new Intent(getActivity(), ShowInfoActivity.class);
        i.putExtra(ShowInfoActivity.InitBundle.SHOW_TVDBID, getShowId());

        if (AndroidUtils.isJellyBeanOrHigher()) {
            Bundle options = ActivityOptions.makeScaleUpAnimation(sourceView, 0, 0,
                    sourceView.getWidth(),
                    sourceView.getHeight()).toBundle();
            getActivity().startActivity(i, options);
        } else {
            startActivity(i);
        }
    }

    @Override
    public void onTraktActionComplete(boolean wasSuccessfull) {
        if (isAdded()) {
            // load new episode, update seasons (if shown)
            onLoadEpisode();
            onUpdateSeasons();
        }
    }

    @Override
    public void onFlagCompleted(FlagAction action, int showId, int itemId, boolean isSuccessful) {
        if (isSuccessful && isAdded()) {
            // load new episode, update seasons (if shown)
            onLoadEpisode();
            onUpdateSeasons();
        }
    }

    interface EpisodeQuery {

        String[] PROJECTION = new String[] {
                Episodes._ID, Shows.REF_SHOW_ID, Episodes.OVERVIEW, Episodes.NUMBER,
                Episodes.SEASON, Episodes.WATCHED, Episodes.FIRSTAIREDMS, Episodes.DIRECTORS,
                Episodes.GUESTSTARS, Episodes.WRITERS, Episodes.RATING, Episodes.IMAGE,
                Episodes.DVDNUMBER, Episodes.TITLE, Seasons.REF_SEASON_ID, Episodes.COLLECTED,
                Episodes.IMDBID
        };

        int _ID = 0;

        int REF_SHOW_ID = 1;

        int OVERVIEW = 2;

        int NUMBER = 3;

        int SEASON = 4;

        int WATCHED = 5;

        int FIRSTAIREDMS = 6;

        int DIRECTORS = 7;

        int GUESTSTARS = 8;

        int WRITERS = 9;

        int RATING = 10;

        int IMAGE = 11;

        int DVDNUMBER = 12;

        int TITLE = 13;

        int REF_SEASON_ID = 14;

        int COLLECTED = 15;

        int IMDBID = 16;
    }
}
