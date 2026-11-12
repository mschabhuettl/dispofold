package app.dispofold.api;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;

import io.quarkus.jackson.ObjectMapperCustomizer;

/** Times are written as {@code HH:MM} (docs/datenformat.md); reading also accepts {@code HH:MM:SS}. */
@Singleton
public class JsonConfig implements ObjectMapperCustomizer {

    /** Runs after the customizer that registers the Java time module, so this serializer wins. */
    @Override
    public int priority() {
        return MINIMUM_PRIORITY;
    }

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.registerModule(new SimpleModule("hh-mm")
                .addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm"))));
    }
}
