package com.shapira.examples.streams.clickstreamenrich;

import com.shapira.examples.streams.clickstreamenrich.model.PageView;
import com.shapira.examples.streams.clickstreamenrich.model.Search;
import com.shapira.examples.streams.clickstreamenrich.model.UserActivity;
import com.shapira.examples.streams.clickstreamenrich.model.UserProfile;
import com.shapira.examples.streams.clickstreamenrich.serde.JsonDeserializer;
import com.shapira.examples.streams.clickstreamenrich.serde.JsonSerializer;
import com.shapira.examples.streams.clickstreamenrich.serde.WrapperSerde;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.StreamsBuilder;

import java.time.Duration;
import java.util.Properties;

public class ClickstreamEnrichment {

    public static void main(String[] args) throws Exception {

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "clicks");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, Constants.BROKER);
        // Since each step in the stream will involve different objects, we can't use default Serde
        // props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Integer().getClass().getName());
        // props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

        // setting offset reset to earliest so that we can re-run the demo code with the same pre-loaded data
        // Note: To re-run the demo, you need to use the offset reset tool:
        // https://cwiki.apache.org/confluence/display/KAFKA/Kafka+Streams+Application+Reset+Tool
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // work-around for an issue around timing of creating internal topics
        // this was resolved in 0.10.2.0 and above
        // don't use in large production apps - this increases network load
        // props.put(CommonClientConfigs.METADATA_MAX_AGE_CONFIG, 500);

        StreamsBuilder builder = new StreamsBuilder();

        KStream<Integer, PageView> views = builder.stream(Constants.PAGE_VIEW_TOPIC, Consumed.with(Serdes.Integer(), new PageViewSerde()));
        KTable<Integer, UserProfile> profiles = builder.table(Constants.USER_PROFILE_TOPIC, Consumed.with(Serdes.Integer(), new ProfileSerde()));
        KStream<Integer, Search> searches = builder.stream(Constants.SEARCH_TOPIC, Consumed.with(Serdes.Integer(), new SearchSerde()));

        //debug
        /*
        views.print(Printed.toSysOut());
        profiles.toStream().print(Printed.toSysOut());
        searches.print(Printed.toSysOut());
         */

        KStream<Integer, UserActivity> viewsWithProfile = views.leftJoin(profiles,
                (page, profile) -> {
                    if (profile != null)
                        return new UserActivity(profile.getUserID(), profile.getUserName(), profile.getZipcode(), profile.getInterests(), "", page.getPage());
                    else
                        return new UserActivity(-1, "", "", null, "", page.getPage());
                });

        KStream<Integer, UserActivity> userActivityKStream = viewsWithProfile.leftJoin(searches,
                (UserActivity userActivity, Search search) -> {
                    if (search != null)
                        userActivity.updateSearch(search.getSearchTerms());
                    else
                        userActivity.updateSearch("");
                    return userActivity;
                },
                JoinWindows.of(Duration.ofSeconds(1)), Joined.with(Serdes.Integer(), new UserActivitySerde(), new SearchSerde()));

        userActivityKStream.to(Constants.USER_ACTIVITY_TOPIC, Produced.with(Serdes.Integer(), new UserActivitySerde()));

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.cleanUp();
        streams.start();

        // usually the stream application would be running forever,
        // in this example we just let it run for some time and stop since the input data is finite.
        Thread.sleep(60000L);
        streams.close();
    }

    static public final class PageViewSerde extends WrapperSerde<PageView> {
        public PageViewSerde() {
            super(new JsonSerializer<PageView>(), new JsonDeserializer<PageView>(PageView.class));
        }
    }

    static public final class ProfileSerde extends WrapperSerde<UserProfile> {
        public ProfileSerde() {
            super(new JsonSerializer<UserProfile>(), new JsonDeserializer<UserProfile>(UserProfile.class));
        }
    }

    static public final class SearchSerde extends WrapperSerde<Search> {
        public SearchSerde() {
            super(new JsonSerializer<Search>(), new JsonDeserializer<Search>(Search.class));
        }
    }

    static public final class UserActivitySerde extends WrapperSerde<UserActivity> {
        public UserActivitySerde() {
            super(new JsonSerializer<UserActivity>(), new JsonDeserializer<UserActivity>(UserActivity.class));
        }
    }
}
