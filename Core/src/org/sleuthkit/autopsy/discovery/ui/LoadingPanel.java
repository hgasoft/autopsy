/*
 * Autopsy
 *
 * Copyright 2020 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
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
package org.sleuthkit.autopsy.discovery.ui;

import javax.swing.JPanel;
import org.apache.commons.lang3.StringUtils;
import org.openide.util.NbBundle;

/**
 * Panel to let user know that the information is loading.
 */
class LoadingPanel extends JPanel {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates new form LoadingPanel
     */
    LoadingPanel() {
        this(null);
    }
    
    @NbBundle.Messages({"LoadingPanel.loading.text=Loading, please wait.",
        "# {0} - resultInfo",
        "LoadingPanel.retrieving.text=Retrieving results for {0}."})
    
    LoadingPanel(String details) {
        initComponents();
        loadingLabel.setText(Bundle.LoadingPanel_loading_text());
        if (!StringUtils.isBlank(details)) {
            detailsLabel.setText(Bundle.LoadingPanel_retrieving_text(details));
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        loadingLabel = new javax.swing.JLabel();
        detailsLabel = new javax.swing.JLabel();

        loadingLabel.setFont(loadingLabel.getFont().deriveFont(loadingLabel.getFont().getSize()+4f));
        loadingLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        loadingLabel.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);

        detailsLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        detailsLabel.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        detailsLabel.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
        detailsLabel.setVerticalTextPosition(javax.swing.SwingConstants.TOP);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(loadingLabel, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(detailsLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 390, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addComponent(loadingLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 48, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(detailsLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 33, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(30, Short.MAX_VALUE))
        );

        detailsLabel.getAccessibleContext().setAccessibleName(org.openide.util.NbBundle.getMessage(LoadingPanel.class, "LoadingPanel.detailsLabel.AccessibleContext.accessibleName")); // NOI18N
    }// </editor-fold>//GEN-END:initComponents


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel detailsLabel;
    private javax.swing.JLabel loadingLabel;
    // End of variables declaration//GEN-END:variables

}