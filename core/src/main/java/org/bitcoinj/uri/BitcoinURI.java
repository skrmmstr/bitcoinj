/*
 * Copyright 2012, 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.uri;

import org.bitcoinj.base.AddressParser;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Address;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * <p>Provides a standard implementation of a Bitcoin URI with support for the following:</p>
 *
 * <ul>
 * <li>URLEncoded URIs (as passed in by IE on the command line)</li>
 * <li>BIP21 names (including the "req-" prefix handling requirements)</li>
 * </ul>
 *
 * <h2>Accepted formats</h2>
 *
 * <p>The following input forms are accepted:</p>
 *
 * <ul>
 * <li>{@code bitcoin:<address>}</li>
 * <li>{@code bitcoin:<address>?<name1>=<value1>&<name2>=<value2>} with multiple
 * additional name/value pairs</li>
 * </ul>
 *
 * <p>The name/value pairs are processed as follows.</p>
 * <ol>
 * <li>URL encoding is stripped and treated as UTF-8</li>
 * <li>names prefixed with {@code req-} are treated as required and if unknown or conflicting cause a parse exception</li>
 * <li>Unknown names not prefixed with {@code req-} are added to a Map, accessible by parameter name</li>
 * <li>Known names not prefixed with {@code req-} are processed unless they are malformed</li>
 * </ol>
 *
 * <p>The following names are known and have the following formats:</p>
 * <ul>
 * <li>{@code amount} decimal value to 8 dp (e.g. 0.12345678) <b>Note that the
 * exponent notation is not supported any more</b></li>
 * <li>{@code label} any URL encoded alphanumeric</li>
 * <li>{@code message} any URL encoded alphanumeric</li>
 * </ul>
 *
 * @author Andreas Schildbach (initial code)
 * @author Jim Burton (enhancements for MultiBit)
 * @author Gary Rowe (BIP21 support)
 * @see <a href="https://github.com/bitcoin/bips/blob/master/bip-0021.mediawiki">BIP 0021</a>
 */
public class BitcoinURI {
    // Not worth turning into an enum
    public static final String FIELD_MESSAGE = "message";
    public static final String FIELD_LABEL = "label";
    public static final String FIELD_AMOUNT = "amount";
    public static final String FIELD_ADDRESS = "address";

    /**
     * URI Scheme for Bitcoin network.
     * @deprecated Use {@link BitcoinNetwork#BITCOIN_SCHEME}.
     */
    @Deprecated
    public static final String BITCOIN_SCHEME = BitcoinNetwork.BITCOIN_SCHEME;
    private static final String ENCODED_SPACE_CHARACTER = "%20";
    private static final String AMPERSAND_SEPARATOR = "&";
    private static final String QUESTION_MARK_SEPARATOR = "?";

    /**
     * Contains all the parameters in the order in which they were processed
     */
    private final Map<String, Object> parameterMap = new LinkedHashMap<>();

    /**
     * Constructs a new BitcoinURI from the given string. Can be for any network.
     *
     * @param uri The raw URI data to be parsed (see class comments for accepted formats)
     * @throws BitcoinURIParseException if the URI is not syntactically or semantically valid.
     */
    public static BitcoinURI of(String uri) throws BitcoinURIParseException {
        // TODO: Discover (via Service Loader mechanism) the correct Network from the URI string
        return new BitcoinURI(uri, null);
    }

    /**
     * Constructs a new object by trying to parse the input as a valid Bitcoin URI.
     *
     * @param uri     The raw URI data to be parsed (see class comments for accepted formats)
     * @param network The network the URI is from
     * @throws BitcoinURIParseException If the input fails Bitcoin URI syntax and semantic checks.
     */
    public static BitcoinURI of(String uri, @Nonnull Network network) throws BitcoinURIParseException {
        return new BitcoinURI(uri, Objects.requireNonNull(network));
    }

    private BitcoinURI(String input, @Nullable Network network) throws BitcoinURIParseException {
        Objects.requireNonNull(input);

        String scheme = network != null ?
                network.uriScheme() :
                BitcoinNetwork.BITCOIN_SCHEME;

        // Attempt to form the URI (fail fast syntax checking to official standards).
        URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException e) {
            throw new BitcoinURIParseException("Bad URI syntax", e);
        }

        // URI is formed as  bitcoin:<address>?<query parameters>
        // blockchain.info generates URIs of non-BIP compliant form bitcoin://address?....
        // We support both until Ben fixes his code.

        // Remove the bitcoin scheme.
        // (Note: getSchemeSpecificPart() is not used as it unescapes the label and parse then fails.
        // For instance with : bitcoin:129mVqKUmJ9uwPxKJBnNdABbuaaNfho4Ha?amount=0.06&label=Tom%20%26%20Jerry
        // the & (%26) in Tom and Jerry gets interpreted as a separator and the label then gets parsed
        // as 'Tom ' instead of 'Tom & Jerry')
        String blockchainInfoScheme = scheme + "://";
        String correctScheme = scheme + ":";
        String schemeSpecificPart;
        final String inputLc = input.toLowerCase(Locale.US);
        if (inputLc.startsWith(blockchainInfoScheme)) {
            schemeSpecificPart = input.substring(blockchainInfoScheme.length());
        } else if (inputLc.startsWith(correctScheme)) {
            schemeSpecificPart = input.substring(correctScheme.length());
        } else {
            throw new BitcoinURIParseException("Unsupported URI scheme: " + uri.getScheme());
        }

        // Split off the address from the rest of the query parameters.
        String[] addressSplitTokens = schemeSpecificPart.split("\\?", 2);
        if (addressSplitTokens.length == 0)
            throw new BitcoinURIParseException("No data found after the bitcoin: prefix");
        String addressToken = addressSplitTokens[0];  // may be empty!

        String[] nameValuePairTokens;
        if (addressSplitTokens.length == 1) {
            // Only an address is specified - use an empty '<name>=<value>' token array.
            nameValuePairTokens = new String[] {};
        } else {
            // Split into '<name>=<value>' tokens.
            nameValuePairTokens = addressSplitTokens[1].split("&");
        }

        // Attempt to parse the rest of the URI parameters.
        parseParameters(nameValuePairTokens);

        if (!addressToken.isEmpty()) {
            // Attempt to parse the addressToken as a Bitcoin address for this network
            try {
                AddressParser parser = network != null
                        ? AddressParser.getDefault(network)
                        : AddressParser.getDefault();
                Address address = parser.parseAddress(addressToken);
                putWithValidation(FIELD_ADDRESS, address);
            } catch (final AddressFormatException e) {
                throw new BitcoinURIParseException("Bad address", e);
            }
        }

        if (addressToken.isEmpty()) {
            throw new BitcoinURIParseException("No address found");
        }
    }

    /**
     * @param nameValuePairTokens The tokens representing the name value pairs (assumed to be
     *                            separated by '=' e.g. 'amount=0.2')
     */
    private void parseParameters(String[] nameValuePairTokens) throws BitcoinURIParseException {
        // Attempt to decode the rest of the tokens into a parameter map.
        for (String nameValuePairToken : nameValuePairTokens) {
            final int sepIndex = nameValuePairToken.indexOf('=');
            if (sepIndex == -1)
                throw new BitcoinURIParseException("Malformed Bitcoin URI - no separator in '" +
                        nameValuePairToken + "'");
            if (sepIndex == 0)
                throw new BitcoinURIParseException("Malformed Bitcoin URI - empty name '" +
                        nameValuePairToken + "'");
            final String nameToken = nameValuePairToken.substring(0, sepIndex).toLowerCase(Locale.ENGLISH);
            final String valueToken = nameValuePairToken.substring(sepIndex + 1);

            // Parse the amount.
            if (FIELD_AMOUNT.equals(nameToken)) {
                // Decode the amount (contains an optional decimal component to 8dp).
                try {
                    Coin amount = Coin.parseCoin(valueToken);
                    if (amount.signum() < 0)
                        throw new OptionalFieldValidationException("negative amount not allowed: " + valueToken);
                    putWithValidation(FIELD_AMOUNT, amount);
                } catch (IllegalArgumentException e) {
                    throw new OptionalFieldValidationException("not a valid amount: " + valueToken, e);
                }
            } else {
                if (nameToken.startsWith("req-")) {
                    // A required parameter that we do not know about.
                    throw new RequiredFieldValidationException("'" + nameToken + "' is required but not known, this URI is not valid");
                } else {
                    // Known fields and unknown parameters that are optional.
                    try {
                        if (valueToken.length() > 0)
                            putWithValidation(nameToken, URLDecoder.decode(valueToken, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException(e); // can't happen
                    }
                }
            }
        }

        // Note to the future: when you want to implement 'req-expires' have a look at commit 410a53791841
        // which had it in.
    }

    /**
     * Put the value against the key in the map checking for duplication. This avoids address field overwrite etc.
     * 
     * @param key The key for the map
     * @param value The value to store
     */
    private void putWithValidation(String key, Object value) throws BitcoinURIParseException {
        if (parameterMap.containsKey(key)) {
            throw new BitcoinURIParseException(String.format(Locale.US, "'%s' is duplicated, URI is invalid", key));
        } else {
            parameterMap.put(key, value);
        }
    }

    /**
     * The Bitcoin address from the URI, if one was present. It's possible to have Bitcoin URI's with no address if a
     * r= payment protocol parameter is specified, though this form is not recommended as older wallets can't understand
     * it.
     */
    @Nullable
    public Address getAddress() {
        return (Address) parameterMap.get(FIELD_ADDRESS);
    }

    /**
     * @return The amount name encoded using a pure integer value based at
     *         10,000,000 units is 1 BTC. May be null if no amount is specified
     */
    public Coin getAmount() {
        return (Coin) parameterMap.get(FIELD_AMOUNT);
    }

    /**
     * @return The label from the URI.
     */
    public String getLabel() {
        return (String) parameterMap.get(FIELD_LABEL);
    }

    /**
     * @return The message from the URI.
     */
    public String getMessage() {
        return (String) parameterMap.get(FIELD_MESSAGE);
    }

    /**
     * @param name The name of the parameter
     * @return The parameter value, or null if not present
     */
    public Object getParameterByName(String name) {
        return parameterMap.get(name);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("BitcoinURI[");
        boolean first = true;
        for (Map.Entry<String, Object> entry : parameterMap.entrySet()) {
            if (first) {
                first = false;
            } else {
                builder.append(",");
            }
            builder.append("'").append(entry.getKey()).append("'=").append("'").append(entry.getValue()).append("'");
        }
        builder.append("]");
        return builder.toString();
    }

    /**
     * Simple Bitcoin URI builder using known good fields.
     *
     * @param address The Bitcoin address
     * @param amount The amount
     * @param label A label
     * @param message A message
     * @return A String containing the Bitcoin URI
     */
    public static String convertToBitcoinURI(Address address, Coin amount,
                                             String label, String message) {
        return convertToBitcoinURI(address.network(), address.toString(), amount, label, message);
    }

    /**
     * Simple Bitcoin URI builder using known good fields.
     *
     * @param network The network the URI is for.
     * @param address The Bitcoin address
     * @param amount The amount
     * @param label A label
     * @param message A message
     * @return A String containing the Bitcoin URI
     */
    public static String convertToBitcoinURI(Network network,
                                             String address, @Nullable Coin amount,
                                             @Nullable String label, @Nullable String message) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(address);
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("Coin must be positive");
        }

        StringBuilder builder = new StringBuilder();
        String scheme = network.uriScheme();
        builder.append(scheme).append(":").append(address);

        boolean questionMarkHasBeenOutput = false;

        if (amount != null) {
            builder.append(QUESTION_MARK_SEPARATOR).append(FIELD_AMOUNT).append("=");
            builder.append(amount.toPlainString());
            questionMarkHasBeenOutput = true;
        }

        if (label != null && !"".equals(label)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);
                questionMarkHasBeenOutput = true;
            }
            builder.append(FIELD_LABEL).append("=").append(encodeURLString(label));
        }

        if (message != null && !"".equals(message)) {
            if (questionMarkHasBeenOutput) {
                builder.append(AMPERSAND_SEPARATOR);
            } else {
                builder.append(QUESTION_MARK_SEPARATOR);
            }
            builder.append(FIELD_MESSAGE).append("=").append(encodeURLString(message));
        }

        return builder.toString();
    }

    /**
     * Encode a string using URL encoding
     *
     * @param stringToEncode The string to URL encode
     */
    static String encodeURLString(String stringToEncode) {
        try {
            return java.net.URLEncoder.encode(stringToEncode, "UTF-8").replace("+", ENCODED_SPACE_CHARACTER);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e); // can't happen
        }
    }
}
