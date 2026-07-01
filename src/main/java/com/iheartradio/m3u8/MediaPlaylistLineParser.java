package com.iheartradio.m3u8;

import com.iheartradio.m3u8.data.*;
import com.iheartradio.m3u8.data.EncryptionData.Builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

class MediaPlaylistLineParser implements LineParser {
    private final IExtTagParser tagParser;
    private final LineParser lineParser;

    MediaPlaylistLineParser(IExtTagParser parser) {
        this(parser, new ExtLineParser(parser));
    }

    MediaPlaylistLineParser(IExtTagParser tagParser, LineParser lineParser) {
        this.tagParser = tagParser;
        this.lineParser = lineParser;
    }

    @Override
    public void parse(String line, ParseState state) throws ParseException {
        if (state.isMaster()) {
            throw ParseException.create(ParseExceptionType.MEDIA_IN_MASTER, tagParser.getTag());
        }

        state.setMedia();
        lineParser.parse(line, state);
    }

    // media playlist tags
    
    static final IExtTagParser EXT_X_ENDLIST = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_ENDLIST_TAG;
        }

        @Override
        public boolean hasData() {
            return false;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            ParseUtil.match(Constants.EXT_X_ENDLIST_PATTERN, line, getTag());
            state.getMedia().endOfList = true;
        }
    };
    
    static final IExtTagParser EXT_X_I_FRAMES_ONLY = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_I_FRAMES_ONLY_TAG;
        }

        @Override
        public boolean hasData() {
            return false;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            
            ParseUtil.match(Constants.EXT_X_I_FRAMES_ONLY_PATTERN, line, getTag());
            
            if (state.getCompatibilityVersion() < 4) {
                throw ParseException.create(ParseExceptionType.REQUIRES_PROTOCOL_VERSION_4_OR_HIGHER, getTag());
            }
            
            state.setIsIframesOnly();
        }
    };
    
    static final IExtTagParser EXT_X_PLAYLIST_TYPE = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_PLAYLIST_TYPE_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }
        
        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final Matcher matcher = ParseUtil.match(Constants.EXT_X_PLAYLIST_TYPE_PATTERN, line, getTag());

            if (state.getMedia().playlistType != null) {
                throw ParseException.create(ParseExceptionType.MULTIPLE_EXT_TAG_INSTANCES, getTag(), line);
            }

            state.getMedia().playlistType = ParseUtil.parseEnum(matcher.group(1), PlaylistType.class, getTag());
        }
    };
    

    static final IExtTagParser EXT_X_PROGRAM_DATE_TIME = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_PROGRAM_DATE_TIME_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }
        
        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final Matcher matcher = ParseUtil.match(Constants.EXT_X_PROGRAM_DATE_TIME_PATTERN, line, getTag());

            if (state.getMedia().programDateTime != null) {
                throw ParseException.create(ParseExceptionType.MULTIPLE_EXT_TAG_INSTANCES, getTag(), line);
            }

            state.getMedia().programDateTime = ParseUtil.parseDateTime(line,getTag());
        }
    };
    
    static final IExtTagParser EXT_X_START = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);
        private final Map<String, AttributeParser<StartData.Builder>> HANDLERS = new HashMap<>();
        
        {
            HANDLERS.put(Constants.TIME_OFFSET, new AttributeParser<StartData.Builder>() {
                @Override
                public void parse(Attribute attribute, StartData.Builder builder, ParseState state) throws ParseException {
                    builder.withTimeOffset(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
            
            HANDLERS.put(Constants.PRECISE, new AttributeParser<StartData.Builder>() {
                @Override
                public void parse(Attribute attribute, StartData.Builder builder, ParseState state) throws ParseException {
                    builder.withPrecise(ParseUtil.parseYesNo(attribute, getTag()));
                }
            });
        }

        @Override
        public String getTag() {
            return Constants.EXT_X_START_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }
        
        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final StartData.Builder builder = new StartData.Builder();
            ParseUtil.parseAttributes(line, builder, state, HANDLERS, getTag());
            final StartData startData = builder.build();

            state.getMedia().setStartData(startData);
        }
    };
    

    static final IExtTagParser EXT_X_TARGETDURATION = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_TARGETDURATION_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final Matcher matcher = ParseUtil.match(Constants.EXT_X_TARGETDURATION_PATTERN, line, getTag());

            if (state.getMedia().targetDuration != null) {
                throw ParseException.create(ParseExceptionType.MULTIPLE_EXT_TAG_INSTANCES, getTag(), line);
            }

            state.getMedia().targetDuration = ParseUtil.parseInt(matcher.group(1), getTag());
        }
    };

    static final IExtTagParser EXT_X_MEDIA_SEQUENCE = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_MEDIA_SEQUENCE_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final Matcher matcher = ParseUtil.match(Constants.EXT_X_MEDIA_SEQUENCE_PATTERN, line, getTag());

            if (state.getMedia().mediaSequenceNumber != null) {
                throw ParseException.create(ParseExceptionType.MULTIPLE_EXT_TAG_INSTANCES, getTag(), line);
            }

            state.getMedia().mediaSequenceNumber = ParseUtil.parseInt(matcher.group(1), getTag());
        }
    };

    static final IExtTagParser EXT_X_ALLOW_CACHE = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_ALLOW_CACHE_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            // deprecated
        }
    };

    // media segment tags

    static final IExtTagParser EXTINF = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXTINF_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final Matcher matcher = ParseUtil.match(Constants.EXTINF_PATTERN, line, getTag());

            state.getMedia().trackInfo = new TrackInfo(ParseUtil.parseFloat(matcher.group(1), getTag()), matcher.group(2));
        }
    };

    static final IExtTagParser EXT_X_DISCONTINUITY = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_DISCONTINUITY_TAG;
        }

        @Override
        public boolean hasData() {
            return false;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            final Matcher matcher = ParseUtil.match(Constants.EXT_X_DISCONTINUITY_PATTERN, line, getTag());
            state.getMedia().hasDiscontinuity = true;
        }
    };

    static final IExtTagParser EXT_X_KEY = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);
        private final Map<String, AttributeParser<EncryptionData.Builder>> HANDLERS = new HashMap<>();

        {
            HANDLERS.put(Constants.METHOD, new AttributeParser<EncryptionData.Builder>() {
                @Override
                public void parse(Attribute attribute, Builder builder, ParseState state) throws ParseException {
                    final EncryptionMethod method = EncryptionMethod.fromValue(attribute.value);

                    if (method == null) {
                        throw ParseException.create(ParseExceptionType.INVALID_ENCRYPTION_METHOD, getTag(), attribute.toString());
                    } else {
                        builder.withMethod(method);
                    }
                }
            });

            HANDLERS.put(Constants.URI, new AttributeParser<EncryptionData.Builder>() {
                @Override
                public void parse(Attribute attribute, Builder builder, ParseState state) throws ParseException {
                    builder.withUri(ParseUtil.decodeUri(ParseUtil.parseQuotedString(attribute.value, getTag()), state.encoding));
                }
            });

            HANDLERS.put(Constants.IV, new AttributeParser<EncryptionData.Builder>() {
                @Override
                public void parse(Attribute attribute, Builder builder, ParseState state) throws ParseException {
                    final List<Byte> initializationVector = ParseUtil.parseHexadecimal(attribute.value, getTag());

                    if ((initializationVector.size() != Constants.IV_SIZE) && 
                        (initializationVector.size() != Constants.IV_SIZE_ALTERNATIVE)) {
                        throw ParseException.create(ParseExceptionType.INVALID_IV_SIZE, getTag(), attribute.toString());
                    }

                    builder.withInitializationVector(initializationVector);
                }
            });

            HANDLERS.put(Constants.KEY_FORMAT, new AttributeParser<EncryptionData.Builder>() {
                @Override
                public void parse(Attribute attribute, Builder builder, ParseState state) throws ParseException {
                    builder.withKeyFormat(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });

            HANDLERS.put(Constants.KEY_FORMAT_VERSIONS, new AttributeParser<EncryptionData.Builder>() {
                @Override
                public void parse(Attribute attribute, Builder builder, ParseState state) throws ParseException {
                    final String[] versionStrings = ParseUtil.parseQuotedString(attribute.value, getTag()).split(Constants.LIST_SEPARATOR);
                    final List<Integer> versions = new ArrayList<>();

                    for (String version : versionStrings) {
                        try {
                            versions.add(Integer.parseInt(version));
                        } catch (NumberFormatException exception) {
                            throw ParseException.create(ParseExceptionType.INVALID_KEY_FORMAT_VERSIONS, getTag(), attribute.toString());
                        }
                    }
                    
                    builder.withKeyFormatVersions(versions);
                }
            });
        }

        @Override
        public String getTag() {
            return Constants.EXT_X_KEY_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }
        
        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final EncryptionData.Builder builder = new EncryptionData.Builder()
                    .withKeyFormat(Constants.DEFAULT_KEY_FORMAT)
                    .withKeyFormatVersions(Constants.DEFAULT_KEY_FORMAT_VERSIONS);

            ParseUtil.parseAttributes(line, builder, state, HANDLERS, getTag());

            final EncryptionData encryptionData = builder.build();

            if (encryptionData.getMethod() != EncryptionMethod.NONE && encryptionData.getUri() == null) {
                throw ParseException.create(ParseExceptionType.MISSING_ENCRYPTION_URI, getTag(), line);
            }

            state.getMedia().encryptionData = encryptionData;
        }
    };

    static final IExtTagParser EXT_X_MAP = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);
        private final Map<String, AttributeParser<MapInfo.Builder>> HANDLERS = new HashMap<>();

        {
            HANDLERS.put(Constants.URI, new AttributeParser<MapInfo.Builder>() {
                @Override
                public void parse(Attribute attribute, MapInfo.Builder builder, ParseState state) throws ParseException {
                    builder.withUri(ParseUtil.decodeUri(ParseUtil.parseQuotedString(attribute.value, getTag()), state.encoding));
                }
            });

            HANDLERS.put(Constants.BYTERANGE, new AttributeParser<MapInfo.Builder>() {
                @Override
                public void parse(Attribute attribute, MapInfo.Builder builder, ParseState state) throws ParseException {
                    Matcher matcher = Constants.EXT_X_BYTERANGE_VALUE_PATTERN.matcher(ParseUtil.parseQuotedString(attribute.value, getTag()));
                    if (!matcher.matches()) {
                        throw ParseException.create(ParseExceptionType.INVALID_BYTERANGE_FORMAT, getTag(), attribute.toString());
                    }

                    builder.withByteRange(ParseUtil.matchByteRange(matcher));
                }
            });
        }

        @Override
        public String getTag() {
            return Constants.EXT_X_MAP;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final MapInfo.Builder builder = new MapInfo.Builder();

            ParseUtil.parseAttributes(line, builder, state, HANDLERS, getTag());
            state.getMedia().mapInfo = builder.build();
        }
    };

    static final IExtTagParser EXT_X_BYTERANGE = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_BYTERANGE_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            final Matcher matcher = ParseUtil.match(Constants.EXT_X_BYTERANGE_PATTERN, line, getTag());
            state.getMedia().byteRange = ParseUtil.matchByteRange(matcher);
        }
    };

    // SSAI cue tags

    static final IExtTagParser EXT_X_CUE_OUT = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_CUE_OUT_TAG;
        }

        @Override
        public boolean hasData() {
            // Duration is optional; line may be #EXT-X-CUE-OUT or #EXT-X-CUE-OUT:60
            return false;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            final Matcher matcher = ParseUtil.match(Constants.EXT_X_CUE_OUT_PATTERN, line, getTag());
            if (matcher.group(1) != null && matcher.group(1).length() > 0) {
                state.getMedia().cueOut = new CueOutData(ParseUtil.parseFloat(matcher.group(1), getTag()));
            } else {
                state.getMedia().cueOut = CueOutData.withoutDuration();
            }
        }
    };

    static final IExtTagParser EXT_X_CUE_IN = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);

        @Override
        public String getTag() {
            return Constants.EXT_X_CUE_IN_TAG;
        }

        @Override
        public boolean hasData() {
            return false;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            ParseUtil.match(Constants.EXT_X_CUE_IN_PATTERN, line, getTag());
            state.getMedia().hasCueIn = true;
        }
    };

    static final IExtTagParser EXT_X_CUE_OUT_CONT = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);
        private final Map<String, AttributeParser<CueOutContData.Builder>> HANDLERS = new HashMap<>();

        {
            HANDLERS.put(Constants.ELAPSED_TIME, new AttributeParser<CueOutContData.Builder>() {
                @Override
                public void parse(Attribute attribute, CueOutContData.Builder builder, ParseState state) throws ParseException {
                    builder.withElapsedTime(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.CUE_DURATION_ATTR, new AttributeParser<CueOutContData.Builder>() {
                @Override
                public void parse(Attribute attribute, CueOutContData.Builder builder, ParseState state) throws ParseException {
                    builder.withDuration(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.DURATION, new AttributeParser<CueOutContData.Builder>() {
                @Override
                public void parse(Attribute attribute, CueOutContData.Builder builder, ParseState state) throws ParseException {
                    builder.withDuration(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.SCTE35, new AttributeParser<CueOutContData.Builder>() {
                @Override
                public void parse(Attribute attribute, CueOutContData.Builder builder, ParseState state) throws ParseException {
                    builder.withScte35(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.SCTE35_OUT, new AttributeParser<CueOutContData.Builder>() {
                @Override
                public void parse(Attribute attribute, CueOutContData.Builder builder, ParseState state) throws ParseException {
                    builder.withScte35(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
        }

        @Override
        public String getTag() {
            return Constants.EXT_X_CUE_OUT_CONT_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);

            final Matcher slashMatcher = Constants.EXT_X_CUE_OUT_CONT_SLASH_PATTERN.matcher(line);
            if (slashMatcher.matches()) {
                state.getMedia().cueOutCont = new CueOutContData(
                        ParseUtil.parseFloat(slashMatcher.group(1), getTag()),
                        ParseUtil.parseFloat(slashMatcher.group(2), getTag()),
                        null);
                return;
            }

            final CueOutContData.Builder builder = new CueOutContData.Builder();
            ParseUtil.parseAttributes(line, builder, state, HANDLERS, getTag());
            state.getMedia().cueOutCont = builder.build();
        }
    };

    static final IExtTagParser EXT_X_DATERANGE = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);
        private final Map<String, AttributeParser<DateRangeData.Builder>> HANDLERS = new HashMap<>();

        {
            HANDLERS.put(Constants.ID, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withId(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.CLASS, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withClassAttribute(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.START_DATE, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    final String startDate = attribute.value;
                    if (!Constants.DATE_TIME_PATTERN.matcher(startDate).matches()) {
                        throw ParseException.create(ParseExceptionType.INVALID_DATE_TIME_FORMAT, getTag(), attribute.toString());
                    }
                    builder.withStartDate(startDate);
                }
            });
            HANDLERS.put(Constants.END_DATE, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    final String endDate = attribute.value;
                    if (!Constants.DATE_TIME_PATTERN.matcher(endDate).matches()) {
                        throw ParseException.create(ParseExceptionType.INVALID_DATE_TIME_FORMAT, getTag(), attribute.toString());
                    }
                    builder.withEndDate(endDate);
                }
            });
            HANDLERS.put(Constants.DURATION, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withDuration(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.PLANNED_DURATION, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withPlannedDuration(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.SCTE35_OUT, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withScte35Out(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.SCTE35_IN, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withScte35In(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.SCTE35_CMD, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withScte35Cmd(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.X_ASSET_URI, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withAssetUri(ParseUtil.decodeUri(ParseUtil.parseQuotedString(attribute.value, getTag()), state.encoding));
                }
            });
            HANDLERS.put(Constants.X_RESTRICTIONS, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withRestrictions(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.X_RESUME_OFFSET, new AttributeParser<DateRangeData.Builder>() {
                @Override
                public void parse(Attribute attribute, DateRangeData.Builder builder, ParseState state) throws ParseException {
                    builder.withResumeOffset(ParseUtil.parseFloat(attribute.value, getTag()));
                }
            });
        }

        @Override
        public String getTag() {
            return Constants.EXT_X_DATERANGE_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            final DateRangeData.Builder builder = new DateRangeData.Builder();
            ParseUtil.parseAttributes(line, builder, state, HANDLERS, getTag());
            final DateRangeData dateRangeData = builder.build();
            if (dateRangeData.getId() == null) {
                throw ParseException.create(ParseExceptionType.MISSING_ATTRIBUTE_NAME, getTag(), line);
            }
            state.getMedia().dateRanges.add(dateRangeData);
        }
    };

    static final IExtTagParser EXT_X_DEFINE = new IExtTagParser() {
        private final LineParser lineParser = new MediaPlaylistLineParser(this);
        private final Map<String, AttributeParser<DefineData.Builder>> HANDLERS = new HashMap<>();

        {
            HANDLERS.put(Constants.DEFINE_NAME, new AttributeParser<DefineData.Builder>() {
                @Override
                public void parse(Attribute attribute, DefineData.Builder builder, ParseState state) throws ParseException {
                    builder.withName(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.DEFINE_VALUE, new AttributeParser<DefineData.Builder>() {
                @Override
                public void parse(Attribute attribute, DefineData.Builder builder, ParseState state) throws ParseException {
                    builder.withValue(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.DEFINE_IMPORT, new AttributeParser<DefineData.Builder>() {
                @Override
                public void parse(Attribute attribute, DefineData.Builder builder, ParseState state) throws ParseException {
                    builder.withImportName(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
            HANDLERS.put(Constants.DEFINE_QUERYPARAM, new AttributeParser<DefineData.Builder>() {
                @Override
                public void parse(Attribute attribute, DefineData.Builder builder, ParseState state) throws ParseException {
                    builder.withQueryParam(ParseUtil.parseQuotedString(attribute.value, getTag()));
                }
            });
        }

        @Override
        public String getTag() {
            return Constants.EXT_X_DEFINE_TAG;
        }

        @Override
        public boolean hasData() {
            return true;
        }

        @Override
        public void parse(String line, ParseState state) throws ParseException {
            lineParser.parse(line, state);
            final DefineData.Builder builder = new DefineData.Builder();
            ParseUtil.parseAttributes(line, builder, state, HANDLERS, getTag());
            final DefineData defineData = builder.build();

            final boolean hasNameValue = defineData.hasName() && defineData.hasValue();
            final boolean hasImport = defineData.hasImportName();
            final boolean hasQueryParam = defineData.hasQueryParam();
            final int modes = (hasNameValue ? 1 : 0) + (hasImport ? 1 : 0) + (hasQueryParam ? 1 : 0);

            if (modes != 1) {
                throw ParseException.create(ParseExceptionType.INVALID_ATTRIBUTE_NAME, getTag(), line);
            }
            if (defineData.hasName() ^ defineData.hasValue()) {
                throw ParseException.create(ParseExceptionType.MISSING_ATTRIBUTE_NAME, getTag(), line);
            }

            state.getMedia().defines.add(defineData);
        }
    };
}
