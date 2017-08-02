package com.tyrion.nucleus.profile;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.EventData;
import com.inductiveautomation.ignition.common.alarming.config.CommonAlarmProperties;
import com.inductiveautomation.ignition.common.config.PropertySet;
import com.inductiveautomation.ignition.common.config.PropertySetBuilder;
import com.inductiveautomation.ignition.common.user.ContactInfo;
import com.inductiveautomation.ignition.common.user.ContactType;
import com.inductiveautomation.ignition.common.user.User;
import com.inductiveautomation.ignition.gateway.alarming.AlarmManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.apache.commons.lang.RandomStringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.python.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by travis.cox on 8/1/2017.
 */
public class NucleusSmsAckManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Maps an alarm key to the alarms and users who were sent that key.
     */
    private final Map<String, AwaitingAcknowledgement> alarmCodes = Maps.newHashMap();

    private final AlarmManager alarmManager;

    private final GatewayContext context;
    private final String profileName;
    private final String hostURL;

    public NucleusSmsAckManager(GatewayContext context, String profileName, String hostURL) {
        this.context = context;
        this.profileName = profileName;
        this.hostURL = hostURL;
        this.alarmManager = context.getAlarmManager();

        context.getExecutionManager().register(
                String.format("NucleusSmsAckManager[%s]", profileName),
                "OrphanedAcknowledgementCleanup",
                new OrphanedAcknowledgementCleanup(),
                1, TimeUnit.MINUTES);

        context.getExecutionManager().register(
                String.format("NucleusSmsAckManager[%s]", profileName),
                "AcknowledgementReceived",
                new AcknowledgementReceived(),
                1, TimeUnit.SECONDS);
    }

    public void onShutdown() {
        context.getExecutionManager().unRegister(
                String.format("NucleusSmsAckManager[%s]", profileName),
                "OrphanedAcknowledgementCleanup");

        context.getExecutionManager().unRegister(
                String.format("NucleusSmsAckManager[%s]", profileName),
                "AcknowledgementReceived");
    }

    /**
     * Register a list of {@link AlarmEvent}s that will be sent to the given {@link User}.
     *
     * @param user            The {@link User} being notified about these {@link AlarmEvent}s.
     * @param alarmEvents     The {@link AlarmEvent}s being sent. An "alarm code" will be generated from this.
     * @return The "alarm code" generated for this transaction. The {@link User} should be sent this alarm code to
     * include in a response SMS that will acknowledge these {@link AlarmEvent}s.
     */
    public String registerAlarms(User user, List<AlarmEvent> alarmEvents) {
        String alarmCode = nextAlarmCode();

        synchronized (alarmCodes) {
            if (alarmCodes.containsKey(alarmCode)) {
                return alarmCode;
            }

            alarmCodes.put(alarmCode, new AwaitingAcknowledgement(user, alarmEvents));
        }

        return alarmCode;
    }

    /**
     * Acknowledge the {@link AlarmEvent}s assigned to {@code alarmCode}, if the code exists and comes from the expected
     * phone number.
     *
     * @param alarmCode      An alarm code.
     * @param incomingNumber The phone number that sent the SMS containing {@code alarmCode}.
     */
    private void acknowledgeAlarm(final String alarmCode, final String incomingNumber, final Date ackTime) {
        synchronized (alarmCodes) {
            if (!alarmCodes.containsKey(alarmCode)) {
                log.warn("Received an incoming SMS for an alarm code that is not registered: '" + alarmCode + "'.");
                return;
            }

            AwaitingAcknowledgement ack = alarmCodes.get(alarmCode);

            User user = ack.user;

            if (incomingNumberBelongsToUser(incomingNumber, user)) {
                alarmCodes.remove(alarmCode);

                PropertySet associatedData = new PropertySetBuilder()
                        .set(CommonAlarmProperties.AckUser, user.getPath())
                        .set(CommonAlarmProperties.AckTime, ackTime)
                        .build();

                List<AlarmEvent> alarmEvents = ack.alarmEvents;

                if (log.isDebugEnabled()) {
                    for (AlarmEvent alarmEvent : alarmEvents) {
                        log.debug("User '" + user.get(User.Username) + "' acknowledged AlarmEvent '" + alarmEvent.getId() + "'.");
                    }
                }

                alarmManager.acknowledge(
                        Collections2.transform(
                                Collections2.filter(alarmEvents, new Predicate<AlarmEvent>() {
                                    @Override
                                    public boolean apply(AlarmEvent alarmEvent) {
                                        return !alarmEvent.isAcked();
                                    }
                                }),
                        new Function<AlarmEvent, UUID>() {
                            @Override
                            public UUID apply(AlarmEvent alarmEvent) {
                                return alarmEvent.getId();
                            }
                        }),
                        new EventData(associatedData));

                return;
            }
        }

        // If we get here, the incoming number didn't belong to somebody the alarm code was actually sent to.
        log.warn("Received an Acknowledge for an alarm from a number (" + incomingNumber + ") that wasn't sent in that alarm!");
    }

    private boolean incomingNumberBelongsToUser(final String incomingNumber, User user) {
        return !Collections2.filter(
                user.getContactInfo(),
                new Predicate<ContactInfo>() {
                    public boolean apply(ContactInfo contactInfo) {
                        try {
                            boolean smsContact = ContactType.SMS.getContactType().equals(contactInfo.getContactType());
                            boolean sameNumber = smsContact && fixPhoneNumber(contactInfo.getValue()).equals(incomingNumber);

                            return smsContact && sameNumber;
                        } catch (Exception e) {
                            log.warn("Error recognizing phone number.", e);
                            return false;
                        }
                    }
                }).isEmpty();
    }

    private String fixPhoneNumber(String phoneNumber){
        if(!phoneNumber.startsWith("1")){
            return "1" + phoneNumber;
        }

        return phoneNumber;
    }

    /**
     * @return A unique (not currently a key of {@link #alarmCodes}), six character, alphanumeric or numeric String to
     * be used as an alarm code.
     */
    private String nextAlarmCode() {
        String key;
        key = RandomStringUtils.randomNumeric(6);

        synchronized (alarmCodes) {
            while (alarmCodes.containsKey(key)) {
                key = RandomStringUtils.randomNumeric(6);
            }
        }

        return key;
    }

    private class AwaitingAcknowledgement {

        private final long createdAtNanos = System.nanoTime();

        private final User user;
        private final List<AlarmEvent> alarmEvents;

        private AwaitingAcknowledgement(User user, List<AlarmEvent> alarmEvents) {
            this.user = user;
            this.alarmEvents = alarmEvents;
        }

    }

    private class OrphanedAcknowledgementCleanup implements Runnable {
        @Override
        public void run() {
            long now = System.nanoTime();

            synchronized (alarmCodes) {
                Iterator<AwaitingAcknowledgement> iterator = alarmCodes.values().iterator();

                while (iterator.hasNext()) {
                    AwaitingAcknowledgement awaiting = iterator.next();
                    long minutesElapsed = TimeUnit.NANOSECONDS.convert(awaiting.createdAtNanos - now, TimeUnit.MINUTES);

                    if (minutesElapsed > 5) iterator.remove();
                }
            }
        }
    }

    private class AcknowledgementReceived implements Runnable {
        @Override
        public void run() {
            boolean foundError = false;

            JSONObject jsonIn = new JSONObject();
            try {
                jsonIn.put("cmd", "read");
            } catch(Exception ignored){}

            HttpURLConnection conn = null;
            InputStream in = null;
            JSONObject jsonOut = null;

            try {
                URL url = new URL(hostURL);
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
                foundError = true;
                log.debug("Error getting acknowledgement buffer", ex);
            }
            finally {
                if(in != null) {
                    try {
                        in.close();
                    }catch(Exception ex){
                        log.debug("Error closing inputstream", ex);
                    }
                }

                if(conn != null) {
                    conn.disconnect();
                }
            }

            if(!foundError) {
                if (jsonOut == null) {
                    log.debug("No return object");
                } else {
                    try {
                        if (jsonOut.has("messages")) {
                            JSONArray messages = jsonOut.getJSONArray("messages");
                            log.trace("Received acknowledgement buffer with " + messages.length() + " message(s)");

                            for (int i = 0; i < messages.length(); i++) {
                                JSONObject message = messages.getJSONObject(i);
                                String number = message.getString("number");
                                String ackCode = message.getString("message");
                                Long utcTime = message.getLong("timestamp");
                                Date ackTime = new Date(utcTime);
                                acknowledgeAlarm(ackCode, number, ackTime);
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("Error parsing JSON object", ex);
                    }
                }
            }
        }
    }

}
