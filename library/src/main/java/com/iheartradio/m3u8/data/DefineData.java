package com.iheartradio.m3u8.data;

import java.util.Objects;

/**
 * Data for an EXT-X-DEFINE tag used to declare macro variables
 * (e.g. user IDs or session tokens for SGAI).
 */
public class DefineData {
    private final String mName;
    private final String mValue;
    private final String mImportName;
    private final String mQueryParam;

    private DefineData(String name, String value, String importName, String queryParam) {
        mName = name;
        mValue = value;
        mImportName = importName;
        mQueryParam = queryParam;
    }

    public String getName() {
        return mName;
    }

    public boolean hasName() {
        return mName != null;
    }

    public String getValue() {
        return mValue;
    }

    public boolean hasValue() {
        return mValue != null;
    }

    public String getImportName() {
        return mImportName;
    }

    public boolean hasImportName() {
        return mImportName != null;
    }

    public String getQueryParam() {
        return mQueryParam;
    }

    public boolean hasQueryParam() {
        return mQueryParam != null;
    }

    public Builder buildUpon() {
        return new Builder(mName, mValue, mImportName, mQueryParam);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mValue, mImportName, mQueryParam);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DefineData)) {
            return false;
        }

        DefineData other = (DefineData) o;

        return Objects.equals(mName, other.mName) &&
                Objects.equals(mValue, other.mValue) &&
                Objects.equals(mImportName, other.mImportName) &&
                Objects.equals(mQueryParam, other.mQueryParam);
    }

    @Override
    public String toString() {
        return "DefineData{" +
                "name='" + mName + '\'' +
                ", value='" + mValue + '\'' +
                ", importName='" + mImportName + '\'' +
                ", queryParam='" + mQueryParam + '\'' +
                '}';
    }

    public static class Builder {
        private String mName;
        private String mValue;
        private String mImportName;
        private String mQueryParam;

        public Builder() {
        }

        private Builder(String name, String value, String importName, String queryParam) {
            mName = name;
            mValue = value;
            mImportName = importName;
            mQueryParam = queryParam;
        }

        public Builder withName(String name) {
            mName = name;
            return this;
        }

        public Builder withValue(String value) {
            mValue = value;
            return this;
        }

        public Builder withImportName(String importName) {
            mImportName = importName;
            return this;
        }

        public Builder withQueryParam(String queryParam) {
            mQueryParam = queryParam;
            return this;
        }

        public DefineData build() {
            return new DefineData(mName, mValue, mImportName, mQueryParam);
        }
    }
}
