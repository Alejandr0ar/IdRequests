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

import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.view.messagecontainer.http.HttpMessageContainer;
import org.zaproxy.zap.view.popup.PopupMenuItemHttpMessageContainer;

/**
 * Right-click context menu item that opens the selected HTTP message in the requester history
 * panel, restoring both the request and the response.
 */
@SuppressWarnings("serial")
public class OpenInPanelMenu extends PopupMenuItemHttpMessageContainer {

    private static final long serialVersionUID = 1L;

    private final ExtensionIdRequests extension;

    public OpenInPanelMenu(ExtensionIdRequests ext, String label) {
        super(label);
        this.extension = ext;
    }

    @Override
    public void performAction(HttpMessage msg) {
        extension.openInPanel(msg);
    }

    @Override
    public boolean isEnableForInvoker(Invoker invoker, HttpMessageContainer httpMessageContainer) {
        return true;
    }

    @Override
    public boolean isSafe() {
        return true;
    }
}
