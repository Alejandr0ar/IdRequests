/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2014 The ZAP Development Team
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

import javax.swing.ImageIcon;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.network.HttpMessage;

/**
 * Extension that provides an HTTP requester panel with request/response history navigation.
 *
 * <p>Users can edit and send HTTP requests, then navigate back and forward through all previously
 * sent requests — each entry restores both the original request and its response.
 */
public class ExtensionIdRequests extends ExtensionAdaptor {

    public static final String NAME = "ExtensionIdRequests";
    protected static final String PREFIX = "idrequests";

    private static final String RESOURCES = "resources";

    private IdRequestsPanel idRequestsPanel;
    private OpenInPanelMenu popupMsgMenuExample;
    private RequesterHistoryInjector requesterHistoryInjector;

    public ExtensionIdRequests() {
        super(NAME);
        setI18nPrefix(PREFIX);
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        super.hook(extensionHook);

        if (hasView()) {
            extensionHook.getHookMenu().addPopupMenuItem(getPopupMsgMenuExample());
            extensionHook.getHookView().addWorkPanel(getIdRequestsPanel());

            // Inject ← → history buttons into Requester's toolbar (no Requester modification needed)
            requesterHistoryInjector = new RequesterHistoryInjector();
            requesterHistoryInjector.install();
        }
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    @Override
    public void unload() {
        super.unload();
        if (requesterHistoryInjector != null) {
            requesterHistoryInjector.uninstall();
        }
    }

    private IdRequestsPanel getIdRequestsPanel() {
        if (idRequestsPanel == null) {
            idRequestsPanel = new IdRequestsPanel();
            idRequestsPanel.setIcon(
                    new ImageIcon(getClass().getResource(RESOURCES + "/cake.png")));
        }
        return idRequestsPanel;
    }

    private OpenInPanelMenu getPopupMsgMenuExample() {
        if (popupMsgMenuExample == null) {
            popupMsgMenuExample =
                    new OpenInPanelMenu(
                            this, Constant.messages.getString(PREFIX + ".popup.title"));
        }
        return popupMsgMenuExample;
    }

    /**
     * Opens the given message in the requester history panel (loads request + response).
     * Called from the right-click context menu.
     */
    public void openInPanel(HttpMessage msg) {
        getIdRequestsPanel().loadHttpMessage(msg);
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString(PREFIX + ".desc");
    }
}
