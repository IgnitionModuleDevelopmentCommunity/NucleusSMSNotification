package com.tyrion.nucleus;

import com.inductiveautomation.ignition.alarming.AlarmNotificationContext;
import com.inductiveautomation.ignition.alarming.common.ModuleMeta;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.user.ContactType;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.services.ModuleServiceConsumer;
import com.tyrion.nucleus.profile.NucleusSmsNotificationProfileType;
import com.tyrion.nucleus.profile.NucleusSmsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GatewayHook extends AbstractGatewayModuleHook implements ModuleServiceConsumer {

    public static final String MODULE_ID = "com.tyrion.nucleus.sms-notification";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private volatile GatewayContext gatewayContext;
    private volatile AlarmNotificationContext notificationContext;

    @Override
    public void setup(GatewayContext context) {
        this.gatewayContext = context;

        context.getModuleServicesManager().subscribe(AlarmNotificationContext.class, this);

        BundleUtil.get().addBundle("NucleusSmsNotification", getClass(), "NucleusSmsNotification");

        context.getUserSourceManager().registerContactType(ContactType.SMS);

        context.getAlarmManager().registerExtendedConfigProperties(ModuleMeta.MODULE_ID,
                NucleusSmsProperties.CUSTOM_MESSAGE);
    }

    @Override
    public void startup(LicenseState activationState) {

    }

    @Override
    public void shutdown() {
        gatewayContext.getModuleServicesManager().unsubscribe(AlarmNotificationContext.class, this);

        if (notificationContext != null) {
            try {
                notificationContext.getAlarmNotificationManager().removeAlarmNotificationProfileType(
                        new NucleusSmsNotificationProfileType());
            } catch (Exception e) {
                log.error("Error removing notification profile.", e);
            }
        }

        BundleUtil.get().removeBundle("NucleusSmsNotification");
    }

    @Override
    public void serviceReady(Class<?> serviceClass) {
        if (serviceClass == AlarmNotificationContext.class) {
            notificationContext = AlarmNotificationContext.class.cast
                    (gatewayContext.getModuleServicesManager().getService(AlarmNotificationContext.class));

            try {
                notificationContext.getAlarmNotificationManager().addAlarmNotificationProfileType(
                        new NucleusSmsNotificationProfileType());
            } catch (Exception e) {
                log.error("Error adding notification profile.", e);
            }
        }
    }

    @Override
    public void serviceShutdown(Class<?> serviceClass) {
        notificationContext = null;
    }

}
