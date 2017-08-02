package com.tyrion.nucleus.profile;

import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.inductiveautomation.ignition.alarming.common.notification.NotificationProfileProperty;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.alarming.notification.NotificationContext;
import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.WellKnownPathTypes;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.config.FallbackPropertyResolver;
import com.inductiveautomation.ignition.common.expressions.parsing.Parser;
import com.inductiveautomation.ignition.common.expressions.parsing.StringParser;
import com.inductiveautomation.ignition.common.i18n.LocalizedString;
import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataQuality;
import com.inductiveautomation.ignition.common.user.ContactInfo;
import com.inductiveautomation.ignition.common.user.ContactType;
import com.inductiveautomation.ignition.common.user.User;
import com.inductiveautomation.ignition.gateway.audit.AuditProfile;
import com.inductiveautomation.ignition.gateway.audit.AuditRecord;
import com.inductiveautomation.ignition.gateway.audit.AuditRecordBuilder;
import com.inductiveautomation.ignition.gateway.expressions.AlarmEventCollectionExpressionParseContext;
import com.inductiveautomation.ignition.gateway.expressions.FormattedExpressionParseContext;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceSession;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus.State;
import com.inductiveautomation.metro.utils.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by travis.cox on 8/1/2017.
 */
public class NucleusSmsNotificationProfile implements AlarmNotificationProfile {

    public static final String DEFAULT_URL = "http://192.168.1.5:1880/alms";
    public static final Integer MAX_SMS_LEN = 160;

    private volatile ProfileStatus profileStatus = ProfileStatus.UNKNOWN;
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final AtomicInteger queueSize = new AtomicInteger(0);

    private final GatewayContext context;
    private final AlarmNotificationProfileRecord profileSettings;

    private final NucleusSmsNotificationProfileSettings settings;
    private String auditProfileName, profileName;
    private final NucleusSmsAckManager ackManager;

    public NucleusSmsNotificationProfile(final GatewayContext context,
                                         final AlarmNotificationProfileRecord profileSettings,
                                         final NucleusSmsNotificationProfileSettings settings) {
        this.context = context;
        this.profileSettings = profileSettings;
        this.settings = settings;
        this.profileName = profileSettings.getName();

        ackManager = new NucleusSmsAckManager(context, profileSettings.getName(), settings.getHostURL());

        //We need to retrieve the audit profile name
        PersistenceSession session = null;
        try {
            //getAuditProfileName gets the AuditProfileRecord and queries the name
            // So, we don't know if the AuditProfileRecord is detached so must reattach
            session = context.getPersistenceInterface().getSession(settings.getDataSet());
            auditProfileName = settings.getAuditProfileName();
        } catch (Exception e) {
            log.error("Error retrieving audit profile name.", e);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    @Override
    public String getName() {
        return profileSettings.getName();
    }

    @Override
    public ProfileStatus getStatus() {
        if (profileStatus.getState() != State.Good) {
            return profileStatus;
        }

        int qs = queueSize.get();

        return qs > 0 ?
                new ProfileStatus(profileStatus.getState(), LocalizedString.createRaw(String.format("%d queued", qs))) :
                profileStatus;
    }

    @Override
    public void onStartup() {
        try {
            profileStatus = ProfileStatus.RUNNING;
        } catch (Exception e) {
            log.error("Error opening connection to Nucleus.", e);
            profileStatus = ProfileStatus.ERRORED;
        }
    }

    @Override
    public void onShutdown() {
        try {
            ackManager.onShutdown();
        } catch (Exception e) {
            log.error("Error closing connection to Nucleus.", e);
        }
    }

    @Override
    public void sendNotification(final NotificationContext notificationContext) {
        User user = notificationContext.getUser();
        Collection<ContactInfo> smsContactInfos =
                Collections2.filter(user.getContactInfo(), new IsSmsContactInfo());
        List<String> numbers = new ArrayList<String>();
        for (ContactInfo smsContactInfo : smsContactInfos) {
            numbers.add(smsContactInfo.getValue());
        }
        String numbersStr = org.apache.commons.lang3.StringUtils.join(numbers, ',');

        String message = evaluateMessageExpression(notificationContext);

        List<AlarmEvent> events = notificationContext.getAlarmEvents();
        String ackCode = ackManager.registerAlarms(user, events);

        boolean allAcked = true;
        for (AlarmEvent event : events) {
            allAcked &= event.isAcked();
        }

        if (!allAcked) {
            message = message + String.format("\nTo acknowledge, reply '%s'.", ackCode);
        }

        Iterable<String> splitMessage = Splitter.fixedLength(MAX_SMS_LEN).split(message);

        // Check if we're in 'test mode'...
        boolean testMode = notificationContext.getOrDefault(NucleusSmsProperties.TEST_MODE);
        if (testMode) {
            log.info("THIS PROFILE IS RUNNING IN TEST MODE. The following sms WOULD have been sent:\n" +
                            "Recipient(s): " + numbersStr + "\n" +
                            "Message: " + message);

            notificationContext.notificationDone();
            return;
        }

        try {
            log.debug("Sending notification to " + numbersStr + ".");
            for (String msg : splitMessage) {
                sendSms(numbers, ackCode, msg);
            }
            audit(true, "Send SMS", notificationContext);
        } catch (Exception e) {
            String errorMessage = String.format("Error sending notification to " + numbersStr + ".");
            log.error(errorMessage, e);
            audit(false, "Send SMS", notificationContext);
            notificationContext.notificationFailed(LocalizedString.createRaw(errorMessage));
            return;
        }

        notificationContext.notificationDone();
    }

    private void sendSms(Collection<String> numbers, String ackCode, String msg) throws Exception {
        JSONObject jsonIn = new JSONObject();
        jsonIn.put("message", msg);
        jsonIn.put("numbers", numbers);
        jsonIn.put("ackCode", ackCode);

        HttpURLConnection conn = null;
        InputStream in = null;
        JSONObject jsonOut = null;

        try {
            URL url = new URL(settings.getHostURL());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestMethod("POST");

            OutputStream os = conn.getOutputStream();
            os.write(jsonIn.toString().getBytes("UTF-8"));
            os.close();

            // read the response
            in = new BufferedInputStream(conn.getInputStream());
            String result = org.apache.commons.io.IOUtils.toString(in, "UTF-8");
            jsonOut = new JSONObject(result);
        }catch(Exception ex){
            throw ex;
        }
        finally {
            if(in != null) {
                in.close();
            }

            if(conn != null) {
                conn.disconnect();
            }
        }

        if (jsonOut == null) {
            throw new Exception("No return object");
        } else {
            boolean success = false;
            if(jsonOut.has("success")) {
                success = jsonOut.getBoolean("success");
            }

            if(!success){
                throw new Exception("Unsuccessful");
            }
        }
    }

    private void audit(boolean success, String eventDesc, NotificationContext notificationContext) {
        if (!StringUtils.isBlank(auditProfileName)) {
            try {
                AuditProfile p = context.getAuditManager().getProfile(auditProfileName);
                if (p == null) {
                    return;
                }
                List<AlarmEvent> alarmEvents = notificationContext.getAlarmEvents();
                for (AlarmEvent event : alarmEvents) {
                    AuditRecord r = new AuditRecordBuilder()
                            .setAction(eventDesc)
                            .setActionTarget(
                                    event.getSource().extend(WellKnownPathTypes.Event, event.getId().toString())
                                            .toString())
                            .setActionValue(success ? "SUCCESS" : "FAILURE")
                            .setActor(notificationContext.getUser().getPath().toString())
                            .setActorHost(profileName)
                            .setOriginatingContext(ApplicationScope.GATEWAY)
                            .setOriginatingSystem("Alarming")
                            .setStatusCode(success ? DataQuality.GOOD_DATA.getIntValue() : 0)
                            .setTimestamp(new Date())
                            .build();
                    p.audit(r);
                }
            } catch (Exception e) {
                log.error("Error auditing email event.", e);
            }
        }
    }

    private String evaluateMessageExpression(NotificationContext notificationContext) {
        Parser parser = new StringParser();

        FallbackPropertyResolver resolver =
                new FallbackPropertyResolver(context.getAlarmManager().getPropertyResolver());

        FormattedExpressionParseContext parseContext =
                new FormattedExpressionParseContext(
                        new AlarmEventCollectionExpressionParseContext(resolver, notificationContext.getAlarmEvents()));

        String expressionString;
        String customMessage = notificationContext.getAlarmEvents().get(0).get(NucleusSmsProperties.CUSTOM_MESSAGE);
        boolean isThrottled = notificationContext.getAlarmEvents().size() > 1;

        if (isThrottled || StringUtils.isBlank(customMessage)) {
            expressionString = isThrottled ?
                    notificationContext.getOrDefault(NucleusSmsProperties.THROTTLED_MESSAGE) :
                    notificationContext.getOrDefault(NucleusSmsProperties.MESSAGE);
        } else {
            expressionString = customMessage;
        }

        String evaluated = expressionString;
        AlarmEventCollectionExpressionParseContext ctx = new AlarmEventCollectionExpressionParseContext(
                new FallbackPropertyResolver(context.getAlarmManager().getPropertyResolver()),
                notificationContext.getAlarmEvents());
        try {
            QualifiedValue value = parser.parse(ctx.expandCollectionReferences(expressionString), parseContext)
                    .execute();
            if (value.getQuality().isGood()) {
                evaluated = TypeUtilities.toString(value.getValue());
            }
        } catch (Exception e) {
            log.error("Error parsing expression '" + expressionString + "'.", e);
        }

        log.trace("Message evaluated to '" + evaluated + "'.");

        return evaluated;
    }

    @Override
    public Collection<NotificationProfileProperty<?>> getProperties() {
        return Lists.<NotificationProfileProperty<?>> newArrayList(
                NucleusSmsProperties.MESSAGE,
                NucleusSmsProperties.THROTTLED_MESSAGE,
                NucleusSmsProperties.TEST_MODE);
    }

    @Override
    public Collection<ContactType> getSupportedContactTypes() {
        return Lists.newArrayList(ContactType.SMS);
    }

    /**
     * A {@link Predicate} that returns true if a {@link ContactInfo}'s {@link ContactType} is SMS.
     */
    private static class IsSmsContactInfo implements Predicate<ContactInfo> {
        @Override
        public boolean apply(ContactInfo contactInfo) {
            return ContactType.SMS.getContactType().equals(contactInfo.getContactType());
        }
    }

}