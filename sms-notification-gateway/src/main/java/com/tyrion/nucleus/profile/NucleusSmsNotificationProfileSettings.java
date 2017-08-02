package com.tyrion.nucleus.profile;

import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.gateway.audit.AuditProfileRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.*;
import simpleorm.dataset.SFieldFlags;

/**
 * Created by travis.cox on 8/1/2017.
 */
public class NucleusSmsNotificationProfileSettings extends PersistentRecord {

    public static final RecordMeta<NucleusSmsNotificationProfileSettings> META =
            new RecordMeta<NucleusSmsNotificationProfileSettings>(
                    NucleusSmsNotificationProfileSettings.class,
                    "NucleusSmsNotificationProfileSettings");

    public static final IdentityField Id = new IdentityField(META);
    public static final LongField ProfileId = new LongField(META, "ProfileId");
    public static final ReferenceField<AlarmNotificationProfileRecord> Profile = new ReferenceField<AlarmNotificationProfileRecord>(
            META,
            AlarmNotificationProfileRecord.META,
            "Profile",
            ProfileId);

    public static final LongField AuditProfileId = new LongField(META, "AuditProfileId");
    public static final ReferenceField<AuditProfileRecord> AuditProfile = new ReferenceField<AuditProfileRecord>(
            META, AuditProfileRecord.META, "AuditProfile", AuditProfileId);

    public static final StringField HostURL = new StringField(META, "HostURL", SFieldFlags.SMANDATORY);

    static final Category Settings = new Category("NucleusSmsNotificationProfileSettings.Category.Settings", 1)
            .include(HostURL);
    static final Category Auditing = new Category("NucleusSmsNotificationProfileSettings.Category.Auditing", 2)
            .include(AuditProfile);
    static {
        Profile.getFormMeta().setVisible(false);

        HostURL.setDefault(NucleusSmsNotificationProfile.DEFAULT_URL);
    }

    @Override
    public RecordMeta<?> getMeta() {
        return META;
    }

    public Long getId() {
        return getLong(Id);
    }

    public String getHostURL() {
        return getString(HostURL);
    }

    public String getAuditProfileName() {
        AuditProfileRecord rec = findReference(AuditProfile);
        return rec == null ? null : rec.getName();
    }

}
