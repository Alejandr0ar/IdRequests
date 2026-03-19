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

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;
import org.zaproxy.zap.utils.DisplayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.db.DatabaseException;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.Message;

/**
 * Injects per-tab history navigation into ZAP's Requester panel without modifying it.
 *
 * <p>Three buttons are added next to Send in each Requester tab:
 * <pre>
 *   [←]  [▼]  [→]  ...  [Send]
 * </pre>
 * <ul>
 *   <li><b>←</b> — loads the previous request/response from this tab's history.</li>
 *   <li><b>▼</b> — opens a numbered dropdown listing every request sent from this tab;
 *       clicking an entry jumps directly to it.</li>
 *   <li><b>→</b> — loads the next request/response from this tab's history.</li>
 * </ul>
 *
 * <p>Each tab maintains <em>independent</em> history. A new tab opened with Ctrl+W
 * starts at position 0 with an empty list.
 */
public class RequesterHistoryInjector {

    private static final Logger LOGGER = LogManager.getLogger(RequesterHistoryInjector.class);

    private static final int POLL_MS  = 300;
    private static final int MAX_POLLS = 40;   // 300 ms × 40 = 12 s max wait

    // ── Per-tab state ─────────────────────────────────────────────────────────

    /** TYPE_ZAP_USER history IDs sent from each tab, in send order (oldest first). */
    private final Map<Component, List<Integer>> tabHistories  = new HashMap<>();

    /** Current navigation position within each tab's history list (−1 = empty). */
    private final Map<Component, Integer>       tabCurrentIdx = new HashMap<>();

    /**
     * [0] = ← button, [1] = ▼ dropdown button, [2] = → button, for each tab.
     * All three must be removed on uninstall.
     */
    private final Map<Component, JButton[]>     tabButtons    = new HashMap<>();

    /** Display labels for history entries: histId → "N  METHOD  /path  (status)". */
    private final Map<Integer, String>          entryLabels   = new HashMap<>();

    /** Tabs that already have our buttons injected. */
    private final Set<Component>                injected      = new HashSet<>();

    /** Kept so we can remove it from the tabbedPane on uninstall. */
    private JTabbedPane  tabbedPane     = null;
    private ChangeListener tabListener  = null;

    // ── Public API ────────────────────────────────────────────────────────────

    public void install() {
        SwingUtilities.invokeLater(this::doInstall);
    }

    public void uninstall() {
        // Stop listening for new tabs
        if (tabbedPane != null && tabListener != null) {
            tabbedPane.removeChangeListener(tabListener);
        }
        tabbedPane  = null;
        tabListener = null;

        // Remove every injected button from Swing hierarchy
        for (JButton[] btns : tabButtons.values()) {
            for (JButton btn : btns) {
                Container parent = btn.getParent();
                if (parent != null) {
                    parent.remove(btn);
                    parent.revalidate();
                    parent.repaint();
                }
            }
        }
        injected.clear();
        tabHistories.clear();
        tabCurrentIdx.clear();
        tabButtons.clear();
        entryLabels.clear();
    }

    // ── Installation ──────────────────────────────────────────────────────────

    private void doInstall() {
        try {
            ExtensionAdaptor extReq =
                    (ExtensionAdaptor) Control.getSingleton()
                            .getExtensionLoader()
                            .getExtension("ExtensionRequester");
            if (extReq == null) {
                LOGGER.debug("ExtensionRequester not found — history buttons not installed");
                return;
            }

            Field panelField = extReq.getClass().getDeclaredField("requesterPanel");
            panelField.setAccessible(true);
            Object requesterPanel = panelField.get(extReq);
            if (requesterPanel == null) return;

            Method getTabbedPane =
                    requesterPanel.getClass().getMethod("getRequesterNumberedTabbedPane");
            JTabbedPane tabbedPane = (JTabbedPane) getTabbedPane.invoke(requesterPanel);

            this.tabbedPane = tabbedPane;

            for (int i = 0; i < tabbedPane.getTabCount() - 1; i++) {
                Component tab = tabbedPane.getComponentAt(i);
                if (tab != null) injectIntoTab(tab);
            }

            tabListener = e -> {
                int idx = tabbedPane.getSelectedIndex();
                if (idx >= 0 && idx < tabbedPane.getTabCount() - 1) {
                    Component tab = tabbedPane.getComponentAt(idx);
                    if (tab != null && !injected.contains(tab)) {
                        injectIntoTab(tab);
                    }
                }
            };
            tabbedPane.addChangeListener(tabListener);

        } catch (NoSuchFieldException e) {
            LOGGER.warn("Could not find 'requesterPanel' — Requester version may differ", e);
        } catch (Exception e) {
            LOGGER.error("Failed to install history buttons into Requester", e);
        }
    }

    // ── Per-tab injection ─────────────────────────────────────────────────────

    private void injectIntoTab(Component tab) {
        if (injected.contains(tab)) return;
        try {
            Field rrField = tab.getClass().getDeclaredField("requestResponsePanel");
            rrField.setAccessible(true);
            Object rp = rrField.get(tab);
            if (rp == null) return;

            // ← back
            JButton backBtn = new JButton(createIcon("arrow-090-medium.png"));
            backBtn.setToolTipText("Petición anterior");
            backBtn.setEnabled(false);
            backBtn.addActionListener(e -> navigate(tab, -1));

            // history list dropdown
            JButton listBtn = new JButton(createIcon("cards-stack.png"));
            listBtn.setToolTipText("Lista de peticiones");
            listBtn.setEnabled(false);
            listBtn.addActionListener(e -> showHistoryPopup(tab, listBtn));

            // → forward
            JButton fwdBtn = new JButton(createIcon("arrow-270-medium.png"));
            fwdBtn.setToolTipText("Petición siguiente");
            fwdBtn.setEnabled(false);
            fwdBtn.addActionListener(e -> navigate(tab, +1));

            Method addToolbarBtn = rp.getClass().getMethod("addToolbarButton", JButton.class);
            addToolbarBtn.invoke(rp, backBtn);
            addToolbarBtn.invoke(rp, listBtn);
            addToolbarBtn.invoke(rp, fwdBtn);

            tabHistories.put(tab, new ArrayList<>());
            tabCurrentIdx.put(tab, -1);
            tabButtons.put(tab, new JButton[]{backBtn, listBtn, fwdBtn});

            hookSendButton(tab);
            injected.add(tab);

        } catch (NoSuchFieldException e) {
            LOGGER.warn("Could not find 'requestResponsePanel' — Requester version may differ", e);
        } catch (Exception e) {
            LOGGER.error("Failed to inject history buttons into tab", e);
        }
    }

    // ── Send detection ────────────────────────────────────────────────────────

    private void hookSendButton(Component tab) {
        try {
            Field btnSendField = findDeclaredField(tab.getClass(), "btnSend");
            if (btnSendField == null) {
                LOGGER.warn("Could not find 'btnSend' field");
                return;
            }
            btnSendField.setAccessible(true);
            JButton sendBtn = (JButton) btnSendField.get(tab);
            if (sendBtn == null) return;

            sendBtn.addActionListener(e -> onSendClicked(tab));
        } catch (Exception e) {
            LOGGER.error("Failed to hook Send button", e);
        }
    }

    private void onSendClicked(Component tab) {
        final int snapshotMaxId;
        try {
            snapshotMaxId = currentMaxTypeZapUserId();
        } catch (DatabaseException e) {
            LOGGER.error("DB error before send snapshot", e);
            return;
        }

        final int[] polls = {0};
        Timer pollTimer = new Timer(POLL_MS, null);
        pollTimer.addActionListener(evt -> {
            try {
                // Only fetch TYPE_ZAP_USER entries that appeared AFTER the snapshot.
                // This excludes proxy traffic (TYPE_PROXIED) whose DB IDs may interleave
                // with the Requester send while we are polling.
                List<Integer> newIds = newTypeZapUserIdsAfter(snapshotMaxId);
                if (!newIds.isEmpty()) {
                    pollTimer.stop();
                    for (int id : newIds) {
                        appendToTabHistory(tab, id);
                    }
                } else if (++polls[0] >= MAX_POLLS) {
                    pollTimer.stop();
                }
            } catch (DatabaseException e) {
                pollTimer.stop();
                LOGGER.error("DB error while polling for new history entry", e);
            }
        });
        pollTimer.start();
    }

    private void appendToTabHistory(Component tab, int histId) {
        List<Integer> hist = tabHistories.computeIfAbsent(tab, k -> new ArrayList<>());

        // Discard forward entries if user is sending from a back-navigated position
        int currentIdx = tabCurrentIdx.getOrDefault(tab, -1);
        while (hist.size() > currentIdx + 1) {
            hist.remove(hist.size() - 1);
        }

        hist.add(histId);
        int newIdx = hist.size() - 1;
        tabCurrentIdx.put(tab, newIdx);

        // Build and cache the display label for this entry
        if (!entryLabels.containsKey(histId)) {
            entryLabels.put(histId, buildLabel(histId));
        }

        updateButtons(tab, newIdx, hist.size());
    }

    // ── Dropdown popup ────────────────────────────────────────────────────────

    /**
     * Builds and shows a popup menu with one item per history entry.
     * The current entry is shown in bold. Clicking an item navigates directly to it.
     */
    private void showHistoryPopup(Component tab, JButton anchor) {
        List<Integer> hist = tabHistories.getOrDefault(tab, new ArrayList<>());
        if (hist.isEmpty()) return;

        int currentIdx = tabCurrentIdx.getOrDefault(tab, -1);
        JPopupMenu popup = new JPopupMenu();

        for (int i = 0; i < hist.size(); i++) {
            int histId  = hist.get(i);
            String base = entryLabels.getOrDefault(histId, "?");
            // Show as "1  GET  /path  (200)"
            String label = (i + 1) + "  " + base;

            JMenuItem item = new JMenuItem(label);
            if (i == currentIdx) {
                // Highlight the currently displayed entry
                item.setFont(item.getFont().deriveFont(Font.BOLD));
            }
            final int idx = i;
            item.addActionListener(e -> navigateTo(tab, idx));
            popup.add(item);
        }

        // Show below the ▼ button
        popup.show(anchor, 0, anchor.getHeight());
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigate(Component tab, int direction) {
        List<Integer> hist = tabHistories.getOrDefault(tab, new ArrayList<>());
        if (hist.isEmpty()) return;

        int currentIdx = tabCurrentIdx.getOrDefault(tab, hist.size() - 1);
        int newIdx = currentIdx + direction;
        if (newIdx < 0 || newIdx >= hist.size()) return;

        try {
            loadMessage(tab, hist.get(newIdx));
            tabCurrentIdx.put(tab, newIdx);
            updateButtons(tab, newIdx, hist.size());
        } catch (HttpMalformedHeaderException | DatabaseException e) {
            LOGGER.error("Navigation error", e);
        }
    }

    /** Jumps directly to a specific index in the tab's history (used by the dropdown). */
    private void navigateTo(Component tab, int idx) {
        List<Integer> hist = tabHistories.getOrDefault(tab, new ArrayList<>());
        if (idx < 0 || idx >= hist.size()) return;

        try {
            loadMessage(tab, hist.get(idx));
            tabCurrentIdx.put(tab, idx);
            updateButtons(tab, idx, hist.size());
        } catch (HttpMalformedHeaderException | DatabaseException e) {
            LOGGER.error("Direct navigation error (idx={})", idx, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Updates enabled state of [←], [▼], [→] for a tab. */
    private void updateButtons(Component tab, int idx, int histSize) {
        JButton[] btns = tabButtons.get(tab);
        if (btns == null) return;
        btns[0].setEnabled(idx > 0);              // ←
        btns[1].setEnabled(histSize > 0);          // ▼ — enabled as soon as there's anything
        btns[2].setEnabled(idx < histSize - 1);    // →
    }

    private void loadMessage(Component tab, int histId)
            throws HttpMalformedHeaderException, DatabaseException {
        HistoryReference ref = new HistoryReference(histId);
        HttpMessage msg = ref.getHttpMessage();
        try {
            Method setMsg = tab.getClass().getMethod("setMessage", Message.class);
            setMsg.invoke(tab, msg);
        } catch (Exception e) {
            LOGGER.error("Failed to call setMessage (histId={})", histId, e);
        }
    }

    /**
     * Builds a compact display label for a history entry: {@code "METHOD  /path  (status)"}.
     * The position number prefix is added by the caller.
     */
    private String buildLabel(int histId) {
        try {
            HistoryReference ref = new HistoryReference(histId);
            HttpMessage msg = ref.getHttpMessage();
            String method = msg.getRequestHeader().getMethod();
            String path;
            try {
                path = msg.getRequestHeader().getURI().getPath();
                if (path == null || path.isEmpty()) path = "/";
            } catch (Exception ex) {
                path = "?";
            }
            int status = msg.getResponseHeader().getStatusCode();
            String statusStr = status > 0 ? "  (" + status + ")" : "";
            return method + "  " + path + statusStr;
        } catch (Exception e) {
            LOGGER.debug("Could not build label for histId={}", histId, e);
            return "[id:" + histId + "]";
        }
    }

    private int currentMaxTypeZapUserId() throws DatabaseException {
        long sessionId = Model.getSingleton().getSession().getSessionId();
        List<Integer> ids = Model.getSingleton().getDb().getTableHistory()
                .getHistoryIdsOfHistType(sessionId, HistoryReference.TYPE_ZAP_USER);
        return ids.isEmpty() ? 0 : ids.get(ids.size() - 1);
    }

    private List<Integer> newTypeZapUserIdsAfter(int afterId) throws DatabaseException {
        long sessionId = Model.getSingleton().getSession().getSessionId();
        return Model.getSingleton().getDb().getTableHistory()
                .getHistoryIdsOfHistTypeStartingAt(
                        sessionId, afterId + 1, HistoryReference.TYPE_ZAP_USER);
    }

    private static ImageIcon createIcon(String relativePath) {
        return DisplayUtils.getScaledIcon(
                RequesterHistoryInjector.class.getResource(
                        "/org/zaproxy/addon/idrequests/resources/images/fugue/" + relativePath));
    }

    private static Field findDeclaredField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
