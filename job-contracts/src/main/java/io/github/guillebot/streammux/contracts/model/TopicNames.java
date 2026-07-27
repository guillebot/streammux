package io.github.guillebot.streammux.contracts.model;

public final class TopicNames {
    public static final String PREFIX = "net.optimum.experimental.streamlens.streammux.";
    public static final String JOB_DEFINITIONS = PREFIX + "jobdefinitions";
    public static final String JOB_LEASES = PREFIX + "jobleases";
    public static final String JOB_STATUS = PREFIX + "jobstatus";
    public static final String JOB_EVENTS = PREFIX + "jobevents";
    public static final String JOB_COMMANDS = PREFIX + "jobcommands";
    public static final String JOB_CATALOG = PREFIX + "jobcatalog";

    private TopicNames() {}
}
