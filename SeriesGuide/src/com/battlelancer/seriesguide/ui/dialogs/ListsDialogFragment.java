
package com.battlelancer.seriesguide.ui.dialogs;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.CursorAdapter;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.CheckedTextView;
import android.widget.ListView;

import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.provider.SeriesContract;
import com.battlelancer.seriesguide.provider.SeriesContract.ListItems;
import com.battlelancer.seriesguide.provider.SeriesContract.Lists;
import com.battlelancer.seriesguide.provider.SeriesGuideDatabase.Tables;
import com.google.analytics.tracking.android.EasyTracker;

import java.util.ArrayList;

public class ListsDialogFragment extends DialogFragment implements
        LoaderManager.LoaderCallbacks<Cursor>, OnItemClickListener {

    public static ListsDialogFragment newInstance(String itemId, int itemType) {
        ListsDialogFragment f = new ListsDialogFragment();
        Bundle args = new Bundle();
        args.putString("itemid", itemId);
        args.putInt("itemtype", itemType);
        f.setArguments(args);
        return f;
    }

    private ListView mListView;
    private ListsAdapter mAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // hide title, use custom theme
        setStyle(STYLE_NO_TITLE, R.style.SeriesGuideTheme_Dialog);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View layout = inflater.inflate(R.layout.list_dialog, null);

        // buttons
        Button dontAddButton = (Button) layout.findViewById(R.id.buttonNegative);
        dontAddButton.setText(android.R.string.cancel);
        dontAddButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        Button addButton = (Button) layout.findViewById(R.id.buttonPositive);
        addButton.setText(android.R.string.ok);
        addButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // add item to selected lists
                String itemId = getArguments().getString("itemid");
                int itemType = getArguments().getInt("itemtype");
                SparseBooleanArray checkedLists = mAdapter.getCheckedPositions();

                final ArrayList<ContentProviderOperation> batch = com.battlelancer.seriesguide.util.Lists
                        .newArrayList();

                for (int position = 0; position < mAdapter.getCount(); position++) {
                    final Cursor listEntry = (Cursor) mAdapter.getItem(position);

                    boolean wasListChecked = !TextUtils.isEmpty(listEntry
                            .getString(ListsQuery.LIST_ITEM_ID));
                    boolean isListChecked = checkedLists.get(position);

                    String listId = listEntry.getString(ListsQuery.LIST_ID);
                    String listItemId = ListItems.generateListItemId(itemId, itemType, listId);

                    if (wasListChecked && !isListChecked) {
                        // remove from list
                        batch.add(ContentProviderOperation.newDelete(
                                ListItems.buildListItemUri(listItemId)).build());
                    } else if (!wasListChecked && isListChecked) {
                        // add to list
                        ContentValues values = new ContentValues();
                        values.put(ListItems.LIST_ITEM_ID, listItemId);
                        values.put(ListItems.ITEM_REF_ID, itemId);
                        values.put(ListItems.TYPE, itemType);
                        values.put(Lists.LIST_ID, listId);
                        batch.add(ContentProviderOperation.newInsert(ListItems.CONTENT_URI)
                                .withValues(values)
                                .build());
                    }
                }

                // apply ops
                try {
                    getActivity().getContentResolver().applyBatch(SeriesContract.CONTENT_AUTHORITY,
                            batch);
                } catch (RemoteException e) {
                    // Failed binder transactions aren't recoverable
                    throw new RuntimeException("Problem applying batch operation", e);
                } catch (OperationApplicationException e) {
                    // Failures like constraint violation aren't recoverable
                    throw new RuntimeException("Problem applying batch operation", e);
                }

                getActivity().getContentResolver().notifyChange(ListItems.CONTENT_WITH_DETAILS_URI,
                        null);

                dismiss();
            }
        });

        // lists list
        mListView = (ListView) layout.findViewById(R.id.list);
        /*
         * As using CHOICE_MODE_MULTIPLE does not seem to work before Jelly
         * Bean, do everything ourselves.
         */
        mListView.setOnItemClickListener(this);

        return layout;
    }

    @Override
    public void onActivityCreated(Bundle arg0) {
        super.onActivityCreated(arg0);

        mAdapter = new ListsAdapter(getActivity(), null, 0);
        mListView.setAdapter(mAdapter);

        getLoaderManager().initLoader(0, getArguments(), this);
    }

    @Override
    public void onStart() {
        super.onStart();
        EasyTracker.getTracker().trackView("Lists Dialog");
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Checkable checkable = (Checkable) view;
        checkable.toggle();
        mAdapter.setItemChecked(position, checkable.isChecked());
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // filter for this item, but keep other lists
        String itemId = args.getString("itemid");
        int itemType = args.getInt("itemtype");

        return new CursorLoader(getActivity(), Lists.buildListsWithListItemUri(ListItems
                .generateListItemIdWildcard(itemId, itemType)),
                ListsQuery.PROJECTION, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }

    private class ListsAdapter extends CursorAdapter {

        private LayoutInflater mInflater;
        private SparseBooleanArray mCheckedItems;

        public ListsAdapter(Context context, Cursor c, int flags) {
            super(context, c, flags);
            mInflater = LayoutInflater.from(context);
            mCheckedItems = new SparseBooleanArray();
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

            View v;
            if (convertView == null) {
                v = newView(mContext, mCursor, parent);
            } else {
                v = convertView;
            }

            CheckedTextView checkedView = (CheckedTextView) v.findViewById(android.R.id.text1);
            checkedView.setText(mCursor.getString(ListsQuery.NAME));

            // check list entry if this item is already added to it
            String itemId = mCursor.getString(ListsQuery.LIST_ITEM_ID);
            boolean isInList = !TextUtils.isEmpty(itemId);
            mCheckedItems.put(position, isInList);
            checkedView.setChecked(isInList);

            return v;
        }

        @Override
        public void bindView(View arg0, Context arg1, Cursor arg2) {
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View v = mInflater.inflate(R.layout.list_dialog_row, parent, false);
            return v;
        }

        public void setItemChecked(int position, boolean value) {
            mCheckedItems.put(position, value);
        }

        public SparseBooleanArray getCheckedPositions() {
            return mCheckedItems;
        }

    }

    interface ListsQuery {
        String[] PROJECTION = new String[] {
                Tables.LISTS + "." + Lists._ID, Tables.LISTS + "." + Lists.LIST_ID, Lists.NAME,
                ListItems.LIST_ITEM_ID
        };

        int LIST_ID = 1;

        int NAME = 2;

        int LIST_ITEM_ID = 3;
    }

    /**
     * Display a dialog which asks if the user wants to add the given show to
     * one or more lists.
     * 
     * @param itemId TVDb/database id of the item to add
     * @param itemType type of the item to add (show, season or episode)
     * @param fm
     */
    public static void showListsDialog(String itemId, int itemType, FragmentManager fm) {
        // DialogFragment.show() will take care of adding the fragment
        // in a transaction. We also want to remove any currently showing
        // dialog, so make our own transaction and take care of that here.
        FragmentTransaction ft = fm.beginTransaction();
        Fragment prev = fm.findFragmentByTag("listsdialog");
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);

        // Create and show the dialog.
        DialogFragment newFragment = ListsDialogFragment.newInstance(itemId, itemType);
        newFragment.show(ft, "listsdialog");
    }
}
