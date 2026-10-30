package com.gambitrate.domain.converter;

import com.gambitrate.domain.TerminationType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;

@Converter
public class TerminationTypeConverter implements AttributeConverter<TerminationType, String> {

    @Override
    public String convertToDatabaseColumn(TerminationType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public TerminationType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TerminationType.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
