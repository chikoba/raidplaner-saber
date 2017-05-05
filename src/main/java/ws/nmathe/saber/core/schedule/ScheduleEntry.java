package ws.nmathe.saber.core.schedule;

import net.dv8tion.jda.core.entities.User;
import org.bson.Document;
import ws.nmathe.saber.Main;
import ws.nmathe.saber.utils.MessageUtilities;
import ws.nmathe.saber.utils.ParsingUtilities;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.TextChannel;
import ws.nmathe.saber.utils.__out;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A ScheduleEntry object represents a currently scheduled entry is either waiting to start or has already started
 * start and end functions are to be triggered upon the scheduled starting time and ending time.
 * reloadDisplay is used to update the displayed 'time until' timer
 */
public class ScheduleEntry
{
    private Integer entryId;                      // 16 bit identifier
    private String entryTitle;                    // the title/name of the event
    private ZonedDateTime entryStart;             // the time when the event starts
    private ZonedDateTime entryEnd;               // the ending time
    private ArrayList<String> entryComments;      // ArrayList of strings that make up the desc
    private Integer entryRepeat;
    private String titleUrl;
    private List<Date> reminders;
    private List<String> rsvpYes;
    private List<String> rsvpNo;

    private String msgId;
    private String chanId;
    private String guildId;
    private String googleId;

    private boolean hasStarted;

    ScheduleEntry(Document entryDocument)
    {
        ZoneId zone = ZoneId.of((String) entryDocument.get("zone"));

        this.entryId = (Integer) entryDocument.get("_id");
        this.entryTitle = (String) entryDocument.get("title");
        this.entryStart = ZonedDateTime.ofInstant(((Date) entryDocument.get("start")).toInstant(), zone);
        this.entryEnd = ZonedDateTime.ofInstant(((Date) entryDocument.get("end")).toInstant(), zone);
        this.entryComments = (ArrayList<String>) entryDocument.get("comments");
        this.entryRepeat = (Integer) entryDocument.get("repeat");
        this.titleUrl = (String) entryDocument.get("url");
        this.reminders = (List<Date>) entryDocument.get("reminders");
        this.rsvpYes = (List<String>) entryDocument.get("rsvp_yes");
        this.rsvpNo = (List<String>) entryDocument.get("rsvp_no");

        this.msgId = (String) entryDocument.get("messageId");
        this.chanId = (String) entryDocument.get("channelId");
        this.guildId = (String) entryDocument.get("guildId");
        this.googleId = (String) entryDocument.get("googleId");
        this.hasStarted = (boolean) entryDocument.get("hasStarted");
    }

    private void checkDelay(Instant time)
    {
        long diff =  Instant.now().toEpochMilli() - time.plusSeconds(60*3).toEpochMilli();
        if(diff > 0)
        {
            User admin = Main.getBotJda().getUserById(Main.getBotSettingsManager().getAdminId());
            MessageUtilities.sendPrivateMsg("An event notification was sent " + diff/(60*1000) + "minutes late!",
                    admin, null);
        }
    }

    /**
     * send an event reminder announcement
     */
    public void remind()
    {
        Message msg = this.getMessageObject();
        if( msg == null )
            return;

        // send the remind announcement
        String remindMsg = ParsingUtilities.
                parseMsgFormat(Main.getScheduleManager().getReminderFormat(this.chanId), this);
        for( TextChannel chan : msg.getGuild().
                getTextChannelsByName(Main.getScheduleManager().getReminderChan(this.chanId), true) )
        {
            MessageUtilities.sendMsg(remindMsg, chan, message -> this.checkDelay(Instant.now()));
        }
    }

    /**
     * Handles when an entries's start time expires
     */
    public void start()
    {
        Message msg = this.getMessageObject();
        if( msg == null )
            return;

        // if the entry's start time is the same as it's end
        // skip to end
        if( this.entryStart.isEqual(this.entryEnd) )
        {
            this.end();
            return;
        }

        // send the start announcement
        String startMsg = ParsingUtilities
                .parseMsgFormat(Main.getScheduleManager().getAnnounceFormat(this.chanId), this);
        for( TextChannel chan : msg.getGuild()
                .getTextChannelsByName(Main.getScheduleManager().getAnnounceChan(this.chanId), true) )
        {
            MessageUtilities.sendMsg(startMsg, chan, message -> this.checkDelay(this.getStart().toInstant()));
        }

        this.reloadDisplay();
        __out.printOut(this.getClass(), "Started event " + this.getTitle() + " at " +
                this.getStart().toLocalTime().truncatedTo(ChronoUnit.MINUTES).toString());
    }

    /**
     * handles when an entry's end time expires
     */
    public void end()
    {
        Message eMsg = this.getMessageObject();
        if( eMsg==null )
            return;

        // send the end announcement
        String endMsg = ParsingUtilities
                .parseMsgFormat(Main.getScheduleManager().getAnnounceFormat(this.chanId), this);
        for( TextChannel chan : eMsg.getGuild().
                getTextChannelsByName(Main.getScheduleManager().getAnnounceChan(this.chanId), true))
        {
            MessageUtilities.sendMsg(endMsg, chan, message -> this.checkDelay(this.getEnd().toInstant()));
        }

        if( this.entryRepeat != 0 ) // find next repeat date and edit the message
        {
            int days = this.daysUntilNextOccurrence();

            // fixes wrap-around at new years
            ZonedDateTime newStart = this.entryStart.plusDays(days).isAfter(this.entryStart) ?
                            this.entryStart.plusDays(days) : this.entryStart.plusDays(days).plusYears(1);
            ZonedDateTime newEnd = this.entryEnd.plusDays(days).isAfter(this.entryEnd) ?
                    this.entryEnd.plusDays(days) : this.entryEnd.plusDays(days).plusYears(1);

            Main.getEntryManager().updateEntry(this.entryId, this.entryTitle, newStart, newEnd, this.entryComments,
                    this.entryRepeat, this.titleUrl, false, this.getMessageObject(), this.googleId,
                    this.rsvpYes, this.rsvpNo);

            Main.getScheduleManager().sortSchedule(this.chanId);
        }
        else // otherwise remove entry and delete the message
        {
            Main.getEntryManager().removeEntry(this.entryId);
            MessageUtilities.deleteMsg( eMsg, null );
        }
        __out.printOut(this.getClass(), "Ended event " + this.getTitle() + " at " +
                this.getEnd().toLocalTime().truncatedTo(ChronoUnit.MINUTES).toString());
    }

    /**
     * Edits the displayed Message to indicate the time remaining until
     * the entry is scheduled to begin/end
     */
    void reloadDisplay()
    {
        Message msg = this.getMessageObject();
        if( msg == null )
            return;

        MessageUtilities.editMsg(
                MessageGenerator.generate(this.entryTitle, this.entryStart, this.entryEnd, this.entryComments,
                        this.entryRepeat, this.titleUrl, this.reminders, this.entryId, this.chanId, this.guildId,
                        this.rsvpYes, this.rsvpNo),
                        msg, null);
    }

    /**
     * determines how many days until the event is scheduled to repeat next from the current time
     * and using the schedule's repeat repeat bitset (eg, 2^0 - sun, 2^1 - mon, etc.)
     * @return days until next occurrence as an int
     */
    private int daysUntilNextOccurrence()
    {
        // if the eighth bit is flagged, the repeat is a daily interval (ie. every two days)
        if((this.entryRepeat & 0b10000000) == 0b10000000)
        {
            return (0b10000000 ^ this.entryRepeat);
        }

        // convert to current day of week to binary representation
        int dayOfWeek = entryStart.getDayOfWeek().getValue();
        int dayAsBitSet;
        if( dayOfWeek == 7 ) //sunday
            dayAsBitSet = 1;
        else                //monday - saturday
            dayAsBitSet = 1<<dayOfWeek;

        // if repeats on same weekday next week
        if( (dayAsBitSet | this.entryRepeat) == dayAsBitSet )
            return 7;

        // if the eighth bit is off, the event repeats on fixed days of the week (ie. on tuesday and wednesday)
        int daysTil = 0;
        // else repeats earlier
        for( int i = 1; i < 7; i++)
        {
            if( dayAsBitSet == 0b1000000 )      //if bitset is SATURDAY, then
                dayAsBitSet = 0b0000001;        //set bitset to SUNDAY
            else
                dayAsBitSet <<= 1;     // else, set to the next day

            if( (dayAsBitSet & this.entryRepeat) == dayAsBitSet )
            {
                daysTil = i;
                break;
            }
        }
        return daysTil; // if this is zero, eRepeat was zero
    }

    public boolean hasStarted()
    {
        return this.hasStarted;
    }

    public String getTitle()
    {
        return this.entryTitle;
    }

    public ZonedDateTime getStart()
    {
        return this.entryStart;
    }

    public ZonedDateTime getEnd()
    {
        return this.entryEnd;
    }

    public ArrayList<String> getComments()
    {
        return this.entryComments;
    }

    public Integer getId()
    {
        return this.entryId;
    }

    public Integer getRepeat()
    {
        return this.entryRepeat;
    }

    public String getTitleUrl()
    {
        return this.titleUrl;
    }

    public List<Date> getReminders()
    {
        return this.reminders;
    }

    public String getGoogleId()
    {
        return this.googleId;
    }

    public String getScheduleID()
    {
        return this.chanId;
    }

    public List<String> getRsvpYes()
    {
        return this.rsvpYes;
    }

    public List<String> getRsvpNo()
    {
        return this.rsvpNo;
    }

    void setMessageObject(Message msg)
    {
        this.chanId = msg.getChannel().getId();
        this.guildId = msg.getGuild().getId();
        this.msgId = msg.getId();
    }

    /**
     * Attempts to retrieve the discord Message, if the message does not exist
     * (or the bot can for any other reason cannot retrieve it) the event entry is
     * removed and null returned
     * @return (Message) if exists, otherwise null
     */
    public Message getMessageObject()
    {
        Message msg;
        try
        {
            msg = Main.getBotJda()
                    .getGuildById(this.guildId)
                    .getTextChannelById(this.chanId)
                    .getMessageById(this.msgId)
                    .complete();
        }
        catch( Exception e )
        {
            Main.getEntryManager().removeEntry(this.getId());
            msg = null;
        }
        return msg;
    }
}
