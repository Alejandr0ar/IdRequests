/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2024 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.addon.idrequests;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintains a navigable list of ZAP {@code HistoryReference} IDs (integers from the database).
 *
 * <p>Storing IDs instead of {@code HttpMessage} objects means:
 * <ul>
 *   <li>No memory bloat — responses are loaded on demand from ZAP's DB.</li>
 *   <li>History survives GC pressure.</li>
 *   <li>Interoperates with ZAP's own history panel.</li>
 * </ul>
 *
 * <p>Navigation follows browser semantics: adding a new entry while positioned in the middle of
 * the list discards everything ahead.
 */
public class RequestHistory {

    private final List<Integer> ids = new ArrayList<>();
    private int currentIndex = -1;

    /**
     * Adds a {@code HistoryReference} ID to the list. Any entries beyond the current position are
     * removed first (same as a browser's back/forward history).
     */
    public void add(int historyId) {
        while (ids.size() > currentIndex + 1) {
            ids.remove(ids.size() - 1);
        }
        ids.add(historyId);
        currentIndex = ids.size() - 1;
    }

    /**
     * Returns the DB ID at the current position, or {@code -1} if the history is empty.
     */
    public int getCurrentId() {
        if (currentIndex < 0 || currentIndex >= ids.size()) return -1;
        return ids.get(currentIndex);
    }

    /**
     * Returns the DB ID at the given 0-based index, or {@code -1} if out of range.
     */
    public int getId(int index) {
        if (index < 0 || index >= ids.size()) return -1;
        return ids.get(index);
    }

    /** Moves one step back and returns the DB ID at the new position. */
    public int goBack() {
        if (canGoBack()) currentIndex--;
        return getCurrentId();
    }

    /** Moves one step forward and returns the DB ID at the new position. */
    public int goForward() {
        if (canGoForward()) currentIndex++;
        return getCurrentId();
    }

    /** Returns {@code true} when there is a previous entry. */
    public boolean canGoBack() {
        return currentIndex > 0;
    }

    /** Returns {@code true} when there is a next entry. */
    public boolean canGoForward() {
        return currentIndex < ids.size() - 1;
    }

    /** Sets the current position to the given 0-based index (used when clicking the list). */
    public void setCurrentIndex(int index) {
        if (index >= 0 && index < ids.size()) currentIndex = index;
    }

    /** Returns the current 0-based index. */
    public int getCurrentIndex() {
        return currentIndex;
    }

    /** Returns the 1-based display position (e.g. 3 → "showing entry 3"). */
    public int getPosition() {
        return currentIndex + 1;
    }

    /** Returns the total number of entries. */
    public int getSize() {
        return ids.size();
    }
}
