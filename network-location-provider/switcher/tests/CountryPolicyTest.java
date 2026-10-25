package com.android.networklocationproviderswitcher;

/** Guard the border-sensitive country classification against erroneous CN suggestions. */
public final class CountryPolicyTest {
    public static void main(String[] args) {
        String[][] cases = {{"460", "cn"}, {"461", "cn"}, {"454", "hk"}, {"455", "mo"},
                {"466", "tw"}, {"310", "us"}, {"440", "jp"}, {"234", "gb"},
                {"", ""}, {"000", ""}, {"4600", ""}, {null, ""}};
        for (String[] sample : cases) {
            if (!sample[1].equals(CountryPolicy.countryForMcc(sample[0])))
                throw new AssertionError("Wrong country for MCC " + sample[0]);
        }
        System.out.println("PASS: " + cases.length + " registered-network country checks");
    }
}
