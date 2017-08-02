package com.tyrion.nucleus.profile;

import static com.inductiveautomation.ignition.common.BundleUtil.i18n;

import com.inductiveautomation.ignition.alarming.common.notification.BasicNotificationProfileProperty;
import com.inductiveautomation.ignition.common.alarming.config.AlarmProperty;
import com.inductiveautomation.ignition.common.alarming.config.BasicAlarmProperty;
import com.inductiveautomation.ignition.common.config.CategorizedProperty;
import com.inductiveautomation.ignition.common.config.CategorizedProperty.Option;
import com.inductiveautomation.ignition.common.i18n.LocalizedString;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by travis.cox on 8/1/2017.
 */
public class NucleusSmsProperties {

    public static final BasicNotificationProfileProperty<String> MESSAGE = new BasicNotificationProfileProperty<String>(
            "message",
            "NucleusSmsNotification." + "Properties.Message.DisplayName",
            null,
            String.class);

    public static final BasicNotificationProfileProperty<String> THROTTLED_MESSAGE = new BasicNotificationProfileProperty<String>(
            "throttledMessage",
            "NucleusSmsNotification." + "Properties.ThrottledMessage.DisplayName",
            null,
            String.class);

    public static final BasicNotificationProfileProperty<Long> TIME_BETWEEN_NOTIFICATIONS = new BasicNotificationProfileProperty<Long>(
            "delayBetweenContact",
            "NucleusSmsNotification." + "Properties.TimeBetweenNotifications.DisplayName",
            null,
            Long.class);

    public static final BasicNotificationProfileProperty<Boolean> TEST_MODE = new BasicNotificationProfileProperty<Boolean>(
            "testMode",
            "NucleusSmsNotification." + "Properties.TestMode.DisplayName",
            null,
            Boolean.class);

    /**
     * EXTENDED CONFIG - These are different than the properties above, they are registered for each alarm through the
     * extended config system
     **/
    public static AlarmProperty<String> CUSTOM_MESSAGE = new BasicAlarmProperty<>("NucleusCustomSmsMessage",
            String.class, "",
            "NucleusSmsNotification.Properties.ExtendedConfig.CustomMessage",
            "NucleusSmsNotification.Properties.ExtendedConfig.Category",
            "NucleusSmsNotification.Properties.ExtendedConfig.CustomMessage.Desc", true, false);

    static {
        MESSAGE.setExpressionSource(true);
        MESSAGE.setDefaultValue(i18n("NucleusSmsNotification." + "Properties.Message.DefaultValue"));

        THROTTLED_MESSAGE.setExpressionSource(true);
        THROTTLED_MESSAGE.setDefaultValue(i18n("NucleusSmsNotification." + "Properties.ThrottledMessage.DefaultValue"));

        TIME_BETWEEN_NOTIFICATIONS.setExpressionSource(true);
        TIME_BETWEEN_NOTIFICATIONS.setDefaultValue(i18n("NucleusSmsNotification." + "Properties.TimeBetweenNotifications.DefaultValue"));

        TEST_MODE.setDefaultValue(false);
        List<Option<Boolean>> options = new ArrayList<Option<Boolean>>();
        options.add(new CategorizedProperty.Option<Boolean>(true, new LocalizedString("words.yes")));
        options.add(new CategorizedProperty.Option<Boolean>(false, new LocalizedString("words.no")));
        TEST_MODE.setOptions(options);
    }

}
