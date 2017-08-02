package com.tyrion.nucleus.profile;

import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileType;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * Created by travis.cox on 8/1/2017.
 */
public class NucleusSmsNotificationProfileType extends AlarmNotificationProfileType {

    public static final String TYPE_ID = "NucleusSms";

    public NucleusSmsNotificationProfileType() {
        super(TYPE_ID,
                "NucleusSmsNotification." + "SmsNotificationProfileType.Name",
                "NucleusSmsNotification." + "SmsNotificationProfileType.Description");
    }

    @Override
    public RecordMeta<? extends PersistentRecord> getSettingsRecordType() {
        return NucleusSmsNotificationProfileSettings.META;
    }

    @Override
    public AlarmNotificationProfile createNewProfile(GatewayContext context,
                                                     AlarmNotificationProfileRecord profileRecord) throws Exception {

        NucleusSmsNotificationProfileSettings settings = findProfileSettingsRecord(context, profileRecord);

        if (settings == null) {
            throw new Exception(
                    String.format("Couldn't find settings record for profile '%s'.", profileRecord.getName()));
        }

        return new NucleusSmsNotificationProfile(context, profileRecord, settings);
    }

}