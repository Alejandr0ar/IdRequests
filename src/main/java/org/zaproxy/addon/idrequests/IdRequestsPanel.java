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

import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import org.zaproxy.zap.utils.DisplayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.db.DatabaseException;
import org.parosproxy.paros.extension.AbstractPanel;
import org.parosproxy.paros.extension.history.ExtensionHistory;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpSender;

/**
 * HTTP requester panel with a navigable history backed by ZAP's database.
 *
 * <p>Each sent request is persisted as a {@link HistoryReference} (TYPE_ZAP_USER). Only the
 * integer ID is kept in memory; the full request/response is loaded from the DB on demand.
 *
 * <pre>
 * ┌──── toolbar ──────────────────────────────────────────────┐
 * │  [←] [→]  2/5  │  [Enviar]                               │
 * ├────────────────┬──────────────────────────────────────────┤
 * │  Historial     │  Petición (editable)                     │
 * │                │                                          │
 * │  1 GET / (200) ├──────────────────────────────────────────┤
 * │  2 POST /login │  Respuesta (read-only)                   │
 * │  3 GET /img    │                                          │
 * └────────────────┴──────────────────────────────────────────┘
 * </pre>
 */
@SuppressWarnings("serial")
public class IdRequestsPanel extends AbstractPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LogManager.getLogger(IdRequestsPanel.class);

    private static final Font MONO_FONT = new Font("Monospaced", Font.PLAIN, 12);
    private static final String DEFAULT_REQUEST =
            "GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: ZAP\r\n\r\n";

    private final RequestHistory history = new RequestHistory();
    private final HttpSender httpSender;

    // Toolbar
    private JButton backButton;
    private JButton forwardButton;
    private JLabel historyLabel;
    private JButton sendButton;

    // Left pane — numbered history list
    private DefaultListModel<String> listModel;
    private JList<String> historyList;

    // Right pane — request editor + response viewer
    private JTextArea requestArea;
    private JTextArea responseArea;

    /** Prevents the list-selection listener from reacting to programmatic selections. */
    private boolean updatingSelection = false;

    public IdRequestsPanel() {
        super();
        setLayout(new BorderLayout());
        setName(Constant.messages.getString(ExtensionIdRequests.PREFIX + ".panel.title"));
        initComponents();
        httpSender = new HttpSender(HttpSender.MANUAL_REQUEST_INITIATOR);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void initComponents() {
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildMainSplit(), BorderLayout.CENTER);
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        backButton = new JButton(createIcon("arrow-090-medium.png"));
        backButton.setToolTipText("Petición anterior");
        backButton.setEnabled(false);
        backButton.addActionListener(e -> navigateBack());

        forwardButton = new JButton(createIcon("arrow-270-medium.png"));
        forwardButton.setToolTipText("Petición siguiente");
        forwardButton.setEnabled(false);
        forwardButton.addActionListener(e -> navigateForward());

        historyLabel = new JLabel("0/0");
        historyLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        sendButton = new JButton(
                Constant.messages.getString(ExtensionIdRequests.PREFIX + ".panel.send"));
        sendButton.addActionListener(e -> sendRequest());

        bar.add(backButton);
        bar.add(forwardButton);
        bar.add(historyLabel);
        bar.addSeparator();
        bar.add(sendButton);
        return bar;
    }

    private JSplitPane buildMainSplit() {
        // ── Left: history list ────────────────────────────────────────────────
        listModel = new DefaultListModel<>();
        historyList = new JList<>(listModel);
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setFont(MONO_FONT);
        historyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingSelection) {
                int idx = historyList.getSelectedIndex();
                if (idx >= 0) {
                    history.setCurrentIndex(idx);
                    loadFromDb(history.getId(idx));
                    updateNavControls();
                }
            }
        });

        JScrollPane listScroll = new JScrollPane(historyList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Historial"));

        // ── Right: request + response ─────────────────────────────────────────
        requestArea = new JTextArea(DEFAULT_REQUEST);
        requestArea.setFont(MONO_FONT);
        JScrollPane reqScroll = new JScrollPane(requestArea);
        reqScroll.setBorder(BorderFactory.createTitledBorder(
                Constant.messages.getString(ExtensionIdRequests.PREFIX + ".panel.request")));

        responseArea = new JTextArea();
        responseArea.setFont(MONO_FONT);
        responseArea.setEditable(false);
        JScrollPane respScroll = new JScrollPane(responseArea);
        respScroll.setBorder(BorderFactory.createTitledBorder(
                Constant.messages.getString(ExtensionIdRequests.PREFIX + ".panel.response")));

        JSplitPane rightSplit =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, reqScroll, respScroll);
        rightSplit.setResizeWeight(0.4);
        rightSplit.setDividerLocation(220);

        JSplitPane mainSplit =
                new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightSplit);
        mainSplit.setDividerLocation(200);
        return mainSplit;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens an existing ZAP history message in the panel. The entry is added to the navigable
     * history using the existing {@link HistoryReference} ID so no duplicate DB entry is created.
     */
    public void loadHttpMessage(HttpMessage msg) {
        showRequest(msg);
        showResponse(msg);

        HistoryReference ref = msg.getHistoryRef();
        if (ref != null) {
            history.add(ref.getHistoryId());
            appendRow(ref.getHistoryId(), msg);
            updateNavControls();
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    private void sendRequest() {
        String raw = requestArea.getText();
        if (raw == null || raw.isBlank()) return;

        // JTextArea uses \n — normalise to CRLF
        raw = raw.replace("\r\n", "\n").replace("\n", "\r\n");

        sendButton.setEnabled(false);
        responseArea.setText(
                Constant.messages.getString(ExtensionIdRequests.PREFIX + ".panel.sending"));

        final String finalRaw = raw;
        new SwingWorker<HttpMessage, Void>() {
            @Override
            protected HttpMessage doInBackground() throws Exception {
                int sep = finalRaw.indexOf("\r\n\r\n");
                String rawHeader =
                        sep >= 0 ? finalRaw.substring(0, sep + 4) : finalRaw + "\r\n\r\n";
                String body = sep >= 0 ? finalRaw.substring(sep + 4) : "";

                HttpRequestHeader header = new HttpRequestHeader(rawHeader);
                HttpMessage message = new HttpMessage(header);
                if (!body.isEmpty()) {
                    message.setRequestBody(body);
                    message.getRequestHeader()
                            .setContentLength(message.getRequestBody().length());
                }
                httpSender.sendAndReceive(message);
                return message;
            }

            @Override
            protected void done() {
                sendButton.setEnabled(true);
                try {
                    HttpMessage msg = get();
                    // Persist in ZAP's database and get the stable ID
                    int histId = persistToDb(msg);
                    if (histId > 0) {
                        history.add(histId);
                        appendRow(histId, msg);
                        updateNavControls();
                    }
                    showResponse(msg);
                } catch (Exception e) {
                    String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    responseArea.setText(
                            Constant.messages.getString(
                                    ExtensionIdRequests.PREFIX + ".panel.error", cause));
                    LOGGER.error("Error sending request", e);
                }
            }
        }.execute();
    }

    /**
     * Saves the message to ZAP's database as a {@code TYPE_ZAP_USER} history entry.
     *
     * @return the new {@code HistoryReference} ID, or {@code -1} on failure.
     */
    private int persistToDb(HttpMessage msg) {
        try {
            HistoryReference ref = new HistoryReference(
                    Model.getSingleton().getSession(),
                    HistoryReference.TYPE_ZAP_USER,
                    msg);
            ExtensionHistory extHistory = Control.getSingleton()
                    .getExtensionLoader()
                    .getExtension(ExtensionHistory.class);
            if (extHistory != null) {
                extHistory.addHistory(ref);
            }
            return ref.getHistoryId();
        } catch (HttpMalformedHeaderException | DatabaseException e) {
            LOGGER.warn("Failed to persist message to DB", e);
            return -1;
        }
    }

    // ── List management ───────────────────────────────────────────────────────

    /**
     * Appends one row to the history list and selects it.
     * Label format: {@code  N  METHOD  /path  (status)}
     */
    private void appendRow(int histId, HttpMessage msg) {
        int n = listModel.size() + 1;
        listModel.addElement(buildLabel(n, histId, msg));
        selectRow(listModel.size() - 1);
    }

    private String buildLabel(int n, int histId, HttpMessage msg) {
        String method = msg.getRequestHeader().getMethod();
        String path;
        try {
            path = msg.getRequestHeader().getURI().getPath();
            if (path == null || path.isEmpty()) path = "/";
        } catch (Exception ex) {
            path = "?";
        }
        int status = msg.getResponseHeader().getStatusCode();
        String statusStr = status > 0 ? " (" + status + ")" : "";
        return n + "  " + method + "  " + path + statusStr + "  [id:" + histId + "]";
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateBack() {
        int id = history.goBack();
        if (id > 0) {
            selectRow(history.getCurrentIndex());
            loadFromDb(id);
            updateNavControls();
        }
    }

    private void navigateForward() {
        int id = history.goForward();
        if (id > 0) {
            selectRow(history.getCurrentIndex());
            loadFromDb(id);
            updateNavControls();
        }
    }

    /**
     * Loads a {@link HttpMessage} from ZAP's database by {@code HistoryReference} ID and
     * displays both request and response.
     */
    private void loadFromDb(int histId) {
        try {
            HistoryReference ref = new HistoryReference(histId);
            HttpMessage msg = ref.getHttpMessage();
            showRequest(msg);
            showResponse(msg);
        } catch (HttpMalformedHeaderException | DatabaseException e) {
            responseArea.setText("Error al cargar id=" + histId + ": " + e.getMessage());
            LOGGER.error("Failed to load history id={}", histId, e);
        }
    }

    /** Selects a list row without firing the selection listener. */
    private void selectRow(int index) {
        updatingSelection = true;
        historyList.setSelectedIndex(index);
        historyList.ensureIndexIsVisible(index);
        updatingSelection = false;
    }

    private void updateNavControls() {
        backButton.setEnabled(history.canGoBack());
        forwardButton.setEnabled(history.canGoForward());
        historyLabel.setText(history.getPosition() + "/" + history.getSize());
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private void showRequest(HttpMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append(msg.getRequestHeader().toString());
        if (msg.getRequestBody().length() > 0) {
            sb.append(msg.getRequestBody().toString());
        }
        requestArea.setText(sb.toString());
        requestArea.setCaretPosition(0);
    }

    private static ImageIcon createIcon(String relativePath) {
        return DisplayUtils.getScaledIcon(
                IdRequestsPanel.class.getResource(
                        "/org/zaproxy/addon/idrequests/resources/images/fugue/" + relativePath));
    }

    private void showResponse(HttpMessage msg) {
        String hdr = msg.getResponseHeader().toString();
        if (hdr == null || hdr.isEmpty()) {
            responseArea.setText(
                    Constant.messages.getString(
                            ExtensionIdRequests.PREFIX + ".panel.noresponse"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hdr);
        if (msg.getResponseBody().length() > 0) {
            sb.append(msg.getResponseBody().toString());
        }
        responseArea.setText(sb.toString());
        responseArea.setCaretPosition(0);
    }
}
