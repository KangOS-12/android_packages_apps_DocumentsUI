/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui.dirlist;

import static com.android.documentsui.Shared.DEBUG;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.VisibleForTesting;
import android.support.v7.widget.RecyclerView;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * MultiSelectManager provides support traditional multi-item selection support to RecyclerView.
 * Additionally it can be configured to restrict selection to a single element, @see
 * #setSelectMode.
 */
public final class MultiSelectManager {

    @IntDef(flag = true, value = {
            MODE_MULTIPLE,
            MODE_SINGLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SelectionMode {}
    public static final int MODE_MULTIPLE = 0;
    public static final int MODE_SINGLE = 1;

    @IntDef({
            RANGE_REGULAR,
            RANGE_PROVISIONAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RangeType {}
    public static final int RANGE_REGULAR = 0;
    public static final int RANGE_PROVISIONAL = 1;

    private static final String TAG = "MultiSelectManager";

    private final Selection mSelection = new Selection();

    private final DocumentsAdapter mAdapter;
    private final List<MultiSelectManager.Callback> mCallbacks = new ArrayList<>(1);

    private @Nullable Range mRanger;
    private boolean mSingleSelect;

    public MultiSelectManager(DocumentsAdapter adapter, @SelectionMode int mode) {

        assert(adapter != null);

        mAdapter = adapter;

        mSingleSelect = mode == MODE_SINGLE;
        mAdapter.registerAdapterDataObserver(
                new RecyclerView.AdapterDataObserver() {

                    private List<String> mModelIds;

                    @Override
                    public void onChanged() {
                        mModelIds = mAdapter.getModelIds();

                        // Update the selection to remove any disappeared IDs.
                        mSelection.cancelProvisionalSelection();
                        mSelection.intersect(mModelIds);
                    }

                    @Override
                    public void onItemRangeChanged(
                            int startPosition, int itemCount, Object payload) {
                        // No change in position. Ignoring.
                    }

                    @Override
                    public void onItemRangeInserted(int startPosition, int itemCount) {
                        mSelection.cancelProvisionalSelection();
                    }

                    @Override
                    public void onItemRangeRemoved(int startPosition, int itemCount) {
                        assert(startPosition >= 0);
                        assert(itemCount > 0);

                        mSelection.cancelProvisionalSelection();
                        // Remove any disappeared IDs from the selection.
                        mSelection.intersect(mModelIds);
                    }

                    @Override
                    public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                        throw new UnsupportedOperationException();
                    }
                });
    }

    void bindContoller(BandController controller) {
        // Provides BandController with access to private mSelection state.
        controller.bindSelection(mSelection);
    }

    /**
     * Adds {@code callback} such that it will be notified when {@code MultiSelectManager}
     * events occur.
     *
     * @param callback
     */
    public void addCallback(MultiSelectManager.Callback callback) {
        mCallbacks.add(callback);
    }

    public boolean hasSelection() {
        return !mSelection.isEmpty();
    }

    /**
     * Returns a Selection object that provides a live view
     * on the current selection.
     *
     * @see #getSelection(Selection) on how to get a snapshot
     *     of the selection that will not reflect future changes
     *     to selection.
     *
     * @return The current selection.
     */
    public Selection getSelection() {
        return mSelection;
    }

    /**
     * Updates {@code dest} to reflect the current selection.
     * @param dest
     *
     * @return The Selection instance passed in, for convenience.
     */
    public Selection getSelection(Selection dest) {
        dest.copyFrom(mSelection);
        return dest;
    }

    public void replaceSelection(Iterable<String> ids) {
        clearSelection();
        setItemsSelected(ids, true);
    }

    /**
     * Returns an unordered array of selected positions, including any
     * provisional selection currently in effect.
     */
    public void restoreSelection(Selection other) {
        setItemsSelected(other.mSelection, true);
        // NOTE: We intentionally don't restore provisional selection. It's provisional.
    }

    /**
     * Sets the selected state of the specified items. Note that the callback will NOT
     * be consulted to see if an item can be selected.
     *
     * @param ids
     * @param selected
     * @return
     */
    public boolean setItemsSelected(Iterable<String> ids, boolean selected) {
        boolean changed = false;
        for (String id: ids) {
            boolean itemChanged = selected ? mSelection.add(id) : mSelection.remove(id);
            if (itemChanged) {
                notifyItemStateChanged(id, selected);
            }
            changed |= itemChanged;
        }
        notifySelectionChanged();
        return changed;
    }

    /**
     * Clears the selection and notifies (even if nothing changes).
     */
    public void clearSelection() {
        clearSelectionQuietly();
        notifySelectionChanged();
    }

    /**
     * Clears the selection, without notifying selection listeners. UI elements still need to be
     * notified about state changes so that they can update their appearance.
     */
    private void clearSelectionQuietly() {
        mRanger = null;

        if (!hasSelection()) {
            return;
        }

        Selection oldSelection = getSelection(new Selection());
        mSelection.clear();

        for (String id: oldSelection.mSelection) {
            notifyItemStateChanged(id, false);
        }
        for (String id: oldSelection.mProvisionalSelection) {
            notifyItemStateChanged(id, false);
        }
    }

    /**
     * Toggles selection on the item with the given model ID.
     *
     * @param modelId
     */
    public void toggleSelection(String modelId) {
        assert(modelId != null);

        boolean changed = false;
        if (mSelection.contains(modelId)) {
            changed = attemptDeselect(modelId);
        } else {
            changed = attemptSelect(modelId);
        }

        if (changed) {
            notifySelectionChanged();
        }
    }

    /**
     * Starts a range selection. If a range selection is already active, this will start a new range
     * selection (which will reset the range anchor).
     *
     * @param pos The anchor position for the selection range.
     */
    void startRangeSelection(int pos) {
        attemptSelect(mAdapter.getModelId(pos));
        setSelectionRangeBegin(pos);
    }

    void snapRangeSelection(int pos) {
        snapRangeSelection(pos, RANGE_REGULAR);
    }

    void snapProvisionalRangeSelection(int pos) {
        snapRangeSelection(pos, RANGE_PROVISIONAL);
    }

    /**
     * Sets the end point for the current range selection, started by a call to
     * {@link #startRangeSelection(int)}. This function should only be called when a range selection
     * is active (see {@link #isRangeSelectionActive()}. Items in the range [anchor, end] will be
     * selected or in provisional select, depending on the type supplied. Note that if the type is
     * provisional select, one should do {@link Selection#applyProvisionalSelection()} at some point
     * before calling on {@link #endRangeSelection()}.
     *
     * @param pos The new end position for the selection range.
     * @param type The type of selection the range should utilize.
     */
    private void snapRangeSelection(int pos, @RangeType int type) {
        if (!isRangeSelectionActive()) {
            throw new IllegalStateException("Range start point not set.");
        }

        mRanger.snapSelection(pos, type);

        // We're being lazy here notifying even when something might not have changed.
        // To make this more correct, we'd need to update the Ranger class to return
        // information about what has changed.
        notifySelectionChanged();
    }

    /**
     * Stops an in-progress range selection. All selection done with
     * {@link #snapRangeSelection(int, int)} with type RANGE_PROVISIONAL will be lost if
     * {@link Selection#applyProvisionalSelection()} is not called beforehand.
     */
    void endRangeSelection() {
        mRanger = null;
        // Clean up in case there was any leftover provisional selection
        mSelection.cancelProvisionalSelection();
    }

    /**
     * @return Whether or not there is a current range selection active.
     */
    boolean isRangeSelectionActive() {
        return mRanger != null;
    }

    /**
     * Sets the magic location at which a selection range begins (the selection anchor). This value
     * is consulted when determining how to extend, and modify selection ranges. Calling this when a
     * range selection is active will reset the range selection.
     */
    void setSelectionRangeBegin(int position) {
        if (position == RecyclerView.NO_POSITION) {
            return;
        }

        if (mSelection.contains(mAdapter.getModelId(position))) {
            mRanger = new Range(this::updateForRange, position);
        }
    }

    /**
     * @param modelId
     * @return True if the update was applied.
     */
    private boolean selectAndNotify(String modelId) {
        boolean changed = mSelection.add(modelId);
        if (changed) {
            notifyItemStateChanged(modelId, true);
        }
        return changed;
    }

    /**
     * @param id
     * @return True if the update was applied.
     */
    private boolean attemptDeselect(String id) {
        assert(id != null);
        if (notifyBeforeItemStateChange(id, false)) {
            mSelection.remove(id);
            notifyItemStateChanged(id, false);
            if (DEBUG) Log.d(TAG, "Selection after deselect: " + mSelection);
            return true;
        } else {
            if (DEBUG) Log.d(TAG, "Select cancelled by listener.");
            return false;
        }
    }

    /**
     * @param id
     * @return True if the update was applied.
     */
    private boolean attemptSelect(String id) {
        assert(id != null);
        boolean canSelect = notifyBeforeItemStateChange(id, true);
        if (!canSelect) {
            return false;
        }
        if (mSingleSelect && hasSelection()) {
            clearSelectionQuietly();
        }

        selectAndNotify(id);
        return true;
    }

    boolean notifyBeforeItemStateChange(String id, boolean nextState) {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            if (!mCallbacks.get(i).onBeforeItemStateChange(id, nextState)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Notifies registered listeners when the selection status of a single item
     * (identified by {@code position}) changes.
     */
    void notifyItemStateChanged(String id, boolean selected) {
        assert(id != null);
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onItemStateChanged(id, selected);
        }
        mAdapter.onItemSelectionChanged(id);
    }

    /**
     * Notifies registered listeners when the selection has changed. This
     * notification should be sent only once a full series of changes
     * is complete, e.g. clearingSelection, or updating the single
     * selection from one item to another.
     */
    void notifySelectionChanged() {
        int lastListener = mCallbacks.size() - 1;
        for (int i = lastListener; i > -1; i--) {
            mCallbacks.get(i).onSelectionChanged();
        }
    }

    private void updateForRange(int begin, int end, boolean selected, @RangeType int type) {
        switch (type) {
            case RANGE_REGULAR:
                updateForRegularRange(begin, end, selected);
                break;
            case RANGE_PROVISIONAL:
                updateForProvisionalRange(begin, end, selected);
                break;
            default:
                throw new IllegalArgumentException("Invalid range type: " + type);
        }
    }

    private void updateForRegularRange(int begin, int end, boolean selected) {
        assert(end >= begin);
        for (int i = begin; i <= end; i++) {
            String id = mAdapter.getModelId(i);
            if (id == null) {
                continue;
            }

            if (selected) {
                boolean canSelect = notifyBeforeItemStateChange(id, true);
                if (canSelect) {
                    if (mSingleSelect && hasSelection()) {
                        clearSelectionQuietly();
                    }
                    selectAndNotify(id);
                }
            } else {
                attemptDeselect(id);
            }
        }
    }

    private void updateForProvisionalRange(int begin, int end, boolean selected) {
        assert (end >= begin);
        for (int i = begin; i <= end; i++) {
            String id = mAdapter.getModelId(i);
            if (id == null) {
                continue;
            }
            if (selected) {
                mSelection.mProvisionalSelection.add(id);
            } else {
                mSelection.mProvisionalSelection.remove(id);
            }
            notifyItemStateChanged(id, selected);
        }
        notifySelectionChanged();
    }

    /**
     * Class providing support for managing range selections.
     */
    private static final class Range {
        private static final int UNDEFINED = -1;

        private final RangeUpdater mUpdater;
        private final int mBegin;
        private int mEnd = UNDEFINED;

        public Range(RangeUpdater updater, int begin) {
            if (DEBUG) Log.d(TAG, "New Ranger created beginning @ " + begin);
            mUpdater = updater;
            mBegin = begin;
        }

        private void snapSelection(int position, @RangeType int type) {
            assert(position != RecyclerView.NO_POSITION);

            if (mEnd == UNDEFINED || mEnd == mBegin) {
                // Reset mEnd so it can be established in establishRange.
                mEnd = UNDEFINED;
                establishRange(position, type);
            } else {
                reviseRange(position, type);
            }
        }

        private void establishRange(int position, @RangeType int type) {
            assert(mEnd == UNDEFINED);

            if (position == mBegin) {
                mEnd = position;
            }

            if (position > mBegin) {
                updateRange(mBegin + 1, position, true, type);
            } else if (position < mBegin) {
                updateRange(position, mBegin - 1, true, type);
            }

            mEnd = position;
        }

        private void reviseRange(int position, @RangeType int type) {
            assert(mEnd != UNDEFINED);
            assert(mBegin != mEnd);

            if (position == mEnd) {
                if (DEBUG) Log.i(TAG, "Skipping no-op revision click on mEndRange.");
            }

            if (mEnd > mBegin) {
                reviseAscendingRange(position, type);
            } else if (mEnd < mBegin) {
                reviseDescendingRange(position, type);
            }
            // the "else" case is covered by checkState at beginning of method.

            mEnd = position;
        }

        /**
         * Updates an existing ascending seleciton.
         * @param position
         */
        private void reviseAscendingRange(int position, @RangeType int type) {
            // Reducing or reversing the range....
            if (position < mEnd) {
                if (position < mBegin) {
                    updateRange(mBegin + 1, mEnd, false, type);
                    updateRange(position, mBegin -1, true, type);
                } else {
                    updateRange(position + 1, mEnd, false, type);
                }
            }

            // Extending the range...
            else if (position > mEnd) {
                updateRange(mEnd + 1, position, true, type);
            }
        }

        private void reviseDescendingRange(int position, @RangeType int type) {
            // Reducing or reversing the range....
            if (position > mEnd) {
                if (position > mBegin) {
                    updateRange(mEnd, mBegin - 1, false, type);
                    updateRange(mBegin + 1, position, true, type);
                } else {
                    updateRange(mEnd, position - 1, false, type);
                }
            }

            // Extending the range...
            else if (position < mEnd) {
                updateRange(position, mEnd - 1, true, type);
            }
        }

        /**
         * Try to set selection state for all elements in range. Not that callbacks can cancel
         * selection of specific items, so some or even all items may not reflect the desired state
         * after the update is complete.
         *
         * @param begin Adapter position for range start (inclusive).
         * @param end Adapter position for range end (inclusive).
         * @param selected New selection state.
         */
        private void updateRange(int begin, int end, boolean selected, @RangeType int type) {
            mUpdater.updateForRange(begin, end, selected, type);
        }

        /*
         * @see {@link MultiSelectManager#updateForRegularRange(int, int , boolean)} and {@link
         * MultiSelectManager#updateForProvisionalRange(int, int, boolean)}
         */
        @FunctionalInterface
        private interface RangeUpdater {
            void updateForRange(int begin, int end, boolean selected, @RangeType int type);
        }
    }

    /**
     * Object representing the current selection. Provides read only access
     * public access, and private write access.
     */
    public static final class Selection implements Iterable<String>, Parcelable {

        // This class tracks selected items by managing two sets: the saved selection, and the total
        // selection. Saved selections are those which have been completed by tapping an item or by
        // completing a band select operation. Provisional selections are selections which have been
        // temporarily created by an in-progress band select operation (once the user releases the
        // mouse button during a band select operation, the selected items become saved). The total
        // selection is the combination of both the saved selection and the provisional
        // selection. Tracking both separately is necessary to ensure that saved selections do not
        // become deselected when they are removed from the provisional selection; for example, if
        // item A is tapped (and selected), then an in-progress band select covers A then uncovers
        // A, A should still be selected as it has been saved. To ensure this behavior, the saved
        // selection must be tracked separately.
        private final Set<String> mSelection;
        private final Set<String> mProvisionalSelection;
        private String mDirectoryKey;

        public Selection() {
            mSelection = new HashSet<String>();
            mProvisionalSelection = new HashSet<String>();
        }

        /**
         * Used by CREATOR.
         */
        private Selection(String directoryKey, Set<String> selection) {
            mDirectoryKey = directoryKey;
            mSelection = selection;
            mProvisionalSelection = new HashSet<String>();
        }

        /**
         * @param id
         * @return true if the position is currently selected.
         */
        public boolean contains(@Nullable String id) {
            return mSelection.contains(id) || mProvisionalSelection.contains(id);
        }

        /**
         * Returns an {@link Iterator} that iterators over the selection, *excluding*
         * any provisional selection.
         *
         * {@inheritDoc}
         */
        @Override
        public Iterator<String> iterator() {
            return mSelection.iterator();
        }

        /**
         * @return size of the selection including both final and provisional selected items.
         */
        public int size() {
            return mSelection.size() + mProvisionalSelection.size();
        }

        /**
         * @return true if the selection is empty.
         */
        public boolean isEmpty() {
            return mSelection.isEmpty() && mProvisionalSelection.isEmpty();
        }

        /**
         * Sets the provisional selection, which is a temporary selection that can be saved,
         * canceled, or adjusted at a later time. When a new provision selection is applied, the old
         * one (if it exists) is abandoned.
         * @return Map of ids added or removed. Added ids have a value of true, removed are false.
         */
        @VisibleForTesting
        protected Map<String, Boolean> setProvisionalSelection(Set<String> newSelection) {
            Map<String, Boolean> delta = new HashMap<>();

            for (String id: mProvisionalSelection) {
                // Mark each item that used to be in the selection but is unsaved and not in the new
                // provisional selection.
                if (!newSelection.contains(id) && !mSelection.contains(id)) {
                    delta.put(id, false);
                }
            }

            for (String id: mSelection) {
                // Mark each item that used to be in the selection but is unsaved and not in the new
                // provisional selection.
                if (!newSelection.contains(id)) {
                    delta.put(id, false);
                }
            }

            for (String id: newSelection) {
                // Mark each item that was not previously in the selection but is in the new
                // provisional selection.
                if (!mSelection.contains(id) && !mProvisionalSelection.contains(id)) {
                    delta.put(id, true);
                }
            }

            // Now, iterate through the changes and actually add/remove them to/from the current
            // selection. This could not be done in the previous loops because changing the size of
            // the selection mid-iteration changes iteration order erroneously.
            for (Map.Entry<String, Boolean> entry: delta.entrySet()) {
                String id = entry.getKey();
                if (entry.getValue()) {
                    mProvisionalSelection.add(id);
                } else {
                    mProvisionalSelection.remove(id);
                }
            }

            return delta;
        }

        /**
         * Saves the existing provisional selection. Once the provisional selection is saved,
         * subsequent provisional selections which are different from this existing one cannot
         * cause items in this existing provisional selection to become deselected.
         */
        @VisibleForTesting
        protected void applyProvisionalSelection() {
            mSelection.addAll(mProvisionalSelection);
            mProvisionalSelection.clear();
        }

        /**
         * Abandons the existing provisional selection so that all items provisionally selected are
         * now deselected.
         */
        @VisibleForTesting
        void cancelProvisionalSelection() {
            mProvisionalSelection.clear();
        }

        /** @hide */
        @VisibleForTesting
        boolean add(String id) {
            if (!mSelection.contains(id)) {
                mSelection.add(id);
                return true;
            }
            return false;
        }

        /** @hide */
        @VisibleForTesting
        boolean remove(String id) {
            if (mSelection.contains(id)) {
                mSelection.remove(id);
                return true;
            }
            return false;
        }

        public void clear() {
            mSelection.clear();
        }

        /**
         * Trims this selection to be the intersection of itself with the set of given IDs.
         */
        public void intersect(Collection<String> ids) {
            mSelection.retainAll(ids);
            mProvisionalSelection.retainAll(ids);
        }

        @VisibleForTesting
        void copyFrom(Selection source) {
            mSelection.clear();
            mSelection.addAll(source.mSelection);

            mProvisionalSelection.clear();
            mProvisionalSelection.addAll(source.mProvisionalSelection);
        }

        @Override
        public String toString() {
            if (size() <= 0) {
                return "size=0, items=[]";
            }

            StringBuilder buffer = new StringBuilder(size() * 28);
            buffer.append("Selection{")
                .append("applied{size=" + mSelection.size())
                .append(", entries=" + mSelection)
                .append("}, provisional{size=" + mProvisionalSelection.size())
                .append(", entries=" + mProvisionalSelection)
                .append("}}");
            return buffer.toString();
        }

        @Override
        public int hashCode() {
            return mSelection.hashCode() ^ mProvisionalSelection.hashCode();
        }

        @Override
        public boolean equals(Object that) {
          if (this == that) {
              return true;
          }

          if (!(that instanceof Selection)) {
              return false;
          }

          return mSelection.equals(((Selection) that).mSelection) &&
                  mProvisionalSelection.equals(((Selection) that).mProvisionalSelection);
        }

        /**
         * Sets the state key for this selection, which allows us to match selections
         * to particular states (of DirectoryFragment). Basically this lets us avoid
         * loading a persisted selection in the wrong directory.
         */
        public void setDirectoryKey(String key) {
            mDirectoryKey = key;
        }

        /**
         * Sets the state key for this selection, which allows us to match selections
         * to particular states (of DirectoryFragment). Basically this lets us avoid
         * loading a persisted selection in the wrong directory.
         */
        public boolean hasDirectoryKey(String key) {
            return key.equals(mDirectoryKey);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mDirectoryKey);
            dest.writeStringList(new ArrayList<>(mSelection));
            // We don't include provisional selection since it is
            // typically coupled to some other runtime state (like a band).
        }

        public static final ClassLoaderCreator<Selection> CREATOR =
                new ClassLoaderCreator<Selection>() {
            @Override
            public Selection createFromParcel(Parcel in) {
                return createFromParcel(in, null);
            }

            @Override
            public Selection createFromParcel(Parcel in, ClassLoader loader) {
                String directoryKey = in.readString();

                ArrayList<String> selected = new ArrayList<>();
                in.readStringList(selected);

                return new Selection(directoryKey, new HashSet<String>(selected));
            }

            @Override
            public Selection[] newArray(int size) {
                return new Selection[size];
            }
        };
    }

    public interface Callback {
        /**
         * Called when an item is selected or unselected while in selection mode.
         *
         * @param position Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is now selected, <code>false</code>
         *                if the item is now unselected.
         */
        public void onItemStateChanged(String id, boolean selected);

        /**
         * Called prior to an item changing state. Callbacks can cancel
         * the change at {@code position} by returning {@code false}.
         *
         * @param id Adapter position of the item that was checked or unchecked
         * @param selected <code>true</code> if the item is to be selected, <code>false</code>
         *                if the item is to be unselected.
         */
        public boolean onBeforeItemStateChange(String id, boolean selected);

        /**
         * Called immediately after completion of any set of changes.
         */
        public void onSelectionChanged();
    }
}