package com.campusplug.api.categories;

public enum CategoryCode {
    ELECTRONICS("Electronics"),
    STATIONERY("Stationery"),
    BAKERY("Bakery"),
    CLOTHING("Clothing"),
    FAST_FOOD("Fast Food"),
    BEVERAGES("Beverages"),
    HOME("Home & Living"),
    BEAUTY("Beauty");

    private final String displayName;

    CategoryCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
