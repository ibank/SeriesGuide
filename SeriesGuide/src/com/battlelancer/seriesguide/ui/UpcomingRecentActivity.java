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
 * 
 */

package com.battlelancer.seriesguide.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.ViewPager;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.ActionBar.Tab;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.battlelancer.seriesguide.items.SearchResult;
import com.battlelancer.seriesguide.ui.UpcomingFragment.UpcomingQuery;
import com.battlelancer.seriesguide.ui.dialogs.AddDialogFragment.OnAddShowListener;
import com.battlelancer.seriesguide.util.TaskManager;
import com.battlelancer.seriesguide.util.Utils;
import com.battlelancer.seriesguide.x.R;
import com.google.analytics.tracking.android.EasyTracker;

import java.util.ArrayList;

public class UpcomingRecentActivity extends BaseActivity implements OnAddShowListener {
    ViewPager mViewPager;

    TabsAdapter mTabsAdapter;

    public interface InitBundle {
        String SELECTED_TAB = "selectedtab";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.upcoming);

        final ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

        ActionBar.Tab upcomingTab = actionBar.newTab().setText(R.string.upcoming);
        ActionBar.Tab recentTab = actionBar.newTab().setText(R.string.recent);

        mViewPager = (ViewPager) findViewById(R.id.pager);

        mTabsAdapter = new TabsAdapter(this, actionBar, mViewPager);
        // upcoming tab
        final Bundle argsUpcoming = new Bundle();
        argsUpcoming.putString(UpcomingFragment.InitBundle.QUERY, UpcomingQuery.QUERY_UPCOMING);
        argsUpcoming.putString(UpcomingFragment.InitBundle.SORTORDER,
                UpcomingQuery.SORTING_UPCOMING);
        argsUpcoming.putString(UpcomingFragment.InitBundle.ANALYTICS_TAG, "Upcoming");
        argsUpcoming.putInt(UpcomingFragment.InitBundle.LOADER_ID, 10);
        argsUpcoming.putInt(UpcomingFragment.InitBundle.EMPTY_STRING_ID, R.string.noupcoming);
        mTabsAdapter.addTab(upcomingTab, UpcomingFragment.class, argsUpcoming);

        // recent tab
        final Bundle argsRecent = new Bundle();
        argsRecent.putString(UpcomingFragment.InitBundle.QUERY, UpcomingQuery.QUERY_RECENT);
        argsRecent.putString(UpcomingFragment.InitBundle.SORTORDER, UpcomingQuery.SORTING_RECENT);
        argsRecent.putString(UpcomingFragment.InitBundle.ANALYTICS_TAG, "Recent");
        argsRecent.putInt(UpcomingFragment.InitBundle.LOADER_ID, 20);
        argsRecent.putInt(UpcomingFragment.InitBundle.EMPTY_STRING_ID, R.string.norecent);
        mTabsAdapter.addTab(recentTab, UpcomingFragment.class, argsRecent);

        // trakt friends tab
        final boolean isTraktSetup = Utils.isTraktCredentialsValid(this);
        if (isTraktSetup) {
            ActionBar.Tab friendsTab = actionBar.newTab().setText(R.string.friends);
            mTabsAdapter.addTab(friendsTab, TraktFriendsFragment.class, null);
        }

        // set starting tab
        int selection = 0;
        if (savedInstanceState != null) {
            selection = savedInstanceState.getInt("index");
        } else {
            Intent intent = getIntent();
            Bundle extras = intent.getExtras();
            if (extras != null) {
                selection = extras.getInt(InitBundle.SELECTED_TAB, 0);
            }
        }
        actionBar.setSelectedNavigationItem(selection);
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("index", getSupportActionBar().getSelectedNavigationIndex());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.activity_menu, menu);

        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());

        // set menu items to current values
        readBooleanPreference(prefs, menu.findItem(R.id.menu_onlyfavorites),
                SeriesGuidePreferences.KEY_ONLYFAVORITES);
        readBooleanPreference(prefs, menu.findItem(R.id.menu_nospecials),
                SeriesGuidePreferences.KEY_ONLY_SEASON_EPISODES);
        readBooleanPreference(prefs, menu.findItem(R.id.menu_nowatched),
                SeriesGuidePreferences.KEY_NOWATCHED);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_onlyfavorites: {
                storeBooleanPreference(item, SeriesGuidePreferences.KEY_ONLYFAVORITES);
                return true;
            }
            case R.id.menu_nospecials: {
                storeBooleanPreference(item, SeriesGuidePreferences.KEY_ONLY_SEASON_EPISODES);
                return true;
            }
            case R.id.menu_nowatched: {
                storeBooleanPreference(item, SeriesGuidePreferences.KEY_NOWATCHED);
                return true;
            }
            case android.R.id.home: {
                /*
                 * force creating a new task if necessary as this activity may
                 * be created from the list widget with SeriesGuide not running
                 * already.
                 */
                Intent i = new Intent(Intent.ACTION_MAIN).setClass(this, ShowsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
                overridePendingTransition(R.anim.fragment_slide_right_enter,
                        R.anim.fragment_slide_right_exit);
            }
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    private void storeBooleanPreference(MenuItem item, String key) {
        item.setChecked(!item.isChecked());
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
        prefs.edit().putBoolean(key, item.isChecked()).commit();
    }

    private void readBooleanPreference(SharedPreferences prefs, MenuItem item, String key) {
        boolean value = prefs.getBoolean(key, false);
        item.setChecked(value);
    }

    /**
     * This is a helper class that implements the management of tabs and all
     * details of connecting a ViewPager with associated TabHost. It relies on a
     * trick. Normally a tab host has a simple API for supplying a View or
     * Intent that each tab will show. This is not sufficient for switching
     * between pages. So instead we make the content part of the tab host 0dp
     * high (it is not shown) and the TabsAdapter supplies its own dummy view to
     * show as the tab content. It listens to changes in tabs, and takes care of
     * switch to the correct paged in the ViewPager whenever the selected tab
     * changes.
     */
    public static class TabsAdapter extends FragmentPagerAdapter implements
            ViewPager.OnPageChangeListener, ActionBar.TabListener {
        private final Context mContext;

        private final ActionBar mActionBar;

        private final ViewPager mViewPager;

        private final ArrayList<String> mTabs = new ArrayList<String>();

        private final ArrayList<Bundle> mArgs = new ArrayList<Bundle>();

        public TabsAdapter(FragmentActivity activity, ActionBar actionBar, ViewPager pager) {
            super(activity.getSupportFragmentManager());
            mContext = activity;
            mActionBar = actionBar;
            mViewPager = pager;
            mViewPager.setAdapter(this);
            mViewPager.setOnPageChangeListener(this);
        }

        public void addTab(ActionBar.Tab tab, Class<?> clss, Bundle args) {
            mTabs.add(clss.getName());
            mArgs.add(args);
            mActionBar.addTab(tab.setTabListener(this));
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mTabs.size();
        }

        @Override
        public Fragment getItem(int position) {
            return Fragment.instantiate(mContext, mTabs.get(position), mArgs.get(position));
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        }

        @Override
        public void onPageSelected(int position) {
            mActionBar.setSelectedNavigationItem(position);
        }

        @Override
        public void onPageScrollStateChanged(int state) {
        }

        @Override
        public void onTabSelected(Tab tab, FragmentTransaction ft) {
            mViewPager.setCurrentItem(tab.getPosition());
        }

        @Override
        public void onTabUnselected(Tab tab, FragmentTransaction ft) {
        }

        @Override
        public void onTabReselected(Tab tab, FragmentTransaction ft) {
        }
    }

    /**
     * Provide a listener for the TraktFriendsFragment.
     */
    @Override
    public void onAddShow(SearchResult show) {
        TaskManager.getInstance(this).performAddTask(show);
    }
}
