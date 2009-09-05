/**
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution, or a commercial license
 * agreement with Jive.
 */

package org.xmpp.packet;

import gnu.inet.encoding.IDNA;
import gnu.inet.encoding.Stringprep;
import gnu.inet.encoding.StringprepException;

import java.io.Serializable;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.jcip.annotations.Immutable;

/**
 * An XMPP address (JID). A JID is made up of a node (generally a username), a domain,
 * and a resource. The node and resource are optional; domain is required. In simple
 * ABNF form:
 *
 * <ul><tt>jid = [ node "@" ] domain [ "/" resource ]</tt></ul>
 *
 * Some sample JID's:
 * <ul>
 *      <li><tt>user@example.com</tt></li>
 *      <li><tt>user@example.com/home</tt></li>
 *      <li><tt>example.com</tt></li>
 * </ul>
 *
 * Each allowable portion of a JID (node, domain, and resource) must not be more
 * than 1023 bytes in length, resulting in a maximum total size (including the '@'
 * and '/' separators) of 3071 bytes.
 *
 * @author Matt Tucker
 */
@Immutable
public class JID implements Comparable<JID>, Serializable {

	private static final long serialVersionUID = 8135170608402192877L;
	
	// Stringprep operations are very expensive. Therefore, we cache node, domain and
    // resource values that have already had stringprep applied so that we can check
    // incoming values against the cache.
    private static final Cache<String> NODEPREP_CACHE = new Cache<String>(10000);
    private static final Cache<String> DOMAINPREP_CACHE = new Cache<String>(500);
    private static final Cache<String> RESOURCEPREP_CACHE = new Cache<String>(10000);

    private final String node;
    private final String domain;
    private final String resource;

    private final String cachedFullJID;
    private final String cachedBareJID;

    /**
     * Escapes the node portion of a JID according to "JID Escaping" (JEP-0106).
     * Escaping replaces characters prohibited by node-prep with escape sequences,
     * as follows:<p>
     *
     * <table border="1">
     * <tr><td><b>Unescaped Character</b></td><td><b>Encoded Sequence</b></td></tr>
     * <tr><td>&lt;space&gt;</td><td>\20</td></tr>
     * <tr><td>"</td><td>\22</td></tr>
     * <tr><td>&</td><td>\26</td></tr>
     * <tr><td>'</td><td>\27</td></tr>
     * <tr><td>/</td><td>\2f</td></tr>
     * <tr><td>:</td><td>\3a</td></tr>
     * <tr><td>&lt;</td><td>\3c</td></tr>
     * <tr><td>&gt;</td><td>\3e</td></tr>
     * <tr><td>@</td><td>\40</td></tr>
     * <tr><td>\</td><td>\5c</td></tr>
     * </table><p>
     *
     * This process is useful when the node comes from an external source that doesn't
     * conform to nodeprep. For example, a username in LDAP may be "Joe Smith". Because
     * the &lt;space&gt; character isn't a valid part of a node, the username should
     * be escaped to "Joe\20Smith" before being made into a JID (e.g. "joe\20smith@example.com"
     * after case-folding, etc. has been applied).<p>
     *
     * All node escaping and un-escaping must be performed manually at the appropriate
     * time; the JID class will not escape or un-escape automatically.
     *
     * @param node the node.
     * @return the escaped version of the node.
     */
    public static String escapeNode(String node) {
        if (node == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder(node.length() + 8);
        for (int i=0, n=node.length(); i<n; i++) {
            char c = node.charAt(i);
            switch (c) {
                case '"': buf.append("\\22"); break;
                case '&': buf.append("\\26"); break;
                case '\'': buf.append("\\27"); break;
                case '/': buf.append("\\2f"); break;
                case ':': buf.append("\\3a"); break;
                case '<': buf.append("\\3c"); break;
                case '>': buf.append("\\3e"); break;
                case '@': buf.append("\\40"); break;
                case '\\': buf.append("\\5c"); break;
                default: {
                    if (Character.isWhitespace(c)) {
                        buf.append("\\20");
                    }
                    else {
                        buf.append(c);
                    }
                }
            }
        }
        return buf.toString();
    }

    /**
     * Un-escapes the node portion of a JID according to "JID Escaping" (JEP-0106).<p>
     * Escaping replaces characters prohibited by node-prep with escape sequences,
     * as follows:<p>
     *
     * <table border="1">
     * <tr><td><b>Unescaped Character</b></td><td><b>Encoded Sequence</b></td></tr>
     * <tr><td>&lt;space&gt;</td><td>\20</td></tr>
     * <tr><td>"</td><td>\22</td></tr>
     * <tr><td>&</td><td>\26</td></tr>
     * <tr><td>'</td><td>\27</td></tr>
     * <tr><td>/</td><td>\2f</td></tr>
     * <tr><td>:</td><td>\3a</td></tr>
     * <tr><td>&lt;</td><td>\3c</td></tr>
     * <tr><td>&gt;</td><td>\3e</td></tr>
     * <tr><td>@</td><td>\40</td></tr>
     * <tr><td>\</td><td>\5c</td></tr>
     * </table><p>
     *
     * This process is useful when the node comes from an external source that doesn't
     * conform to nodeprep. For example, a username in LDAP may be "Joe Smith". Because
     * the &lt;space&gt; character isn't a valid part of a node, the username should
     * be escaped to "Joe\20Smith" before being made into a JID (e.g. "joe\20smith@example.com"
     * after case-folding, etc. has been applied).<p>
     *
     * All node escaping and un-escaping must be performed manually at the appropriate
     * time; the JID class will not escape or un-escape automatically.
     *
     * @param node the escaped version of the node.
     * @return the un-escaped version of the node.
     */
    public static String unescapeNode(String node) {
        if (node == null) {
            return null;
        }
        char [] nodeChars = node.toCharArray();
        StringBuilder buf = new StringBuilder(nodeChars.length);
        for (int i=0, n=nodeChars.length; i<n; i++) {
            compare: {
                char c = node.charAt(i);
                if (c == '\\' && i+2<n) {
                    char c2 = nodeChars[i+1];
                    char c3 = nodeChars[i+2];
                    if (c2 == '2') {
                        switch (c3) {
                            case '0': buf.append(' '); i+=2; break compare;
                            case '2': buf.append('"'); i+=2; break compare;
                            case '6': buf.append('&'); i+=2; break compare;
                            case '7': buf.append('\''); i+=2; break compare;
                            case 'f': buf.append('/'); i+=2; break compare;
                        }
                    }
                    else if (c2 == '3') {
                        switch (c3) {
                            case 'a': buf.append(':'); i+=2; break compare;
                            case 'c': buf.append('<'); i+=2; break compare;
                            case 'e': buf.append('>'); i+=2; break compare;
                        }
                    }
                    else if (c2 == '4') {
                        if (c3 == '0') {
                            buf.append("@");
                            i+=2;
                            break compare;
                        }
                    }
                    else if (c2 == '5') {
                        if (c3 == 'c') {
                            buf.append("\\");
                            i+=2;
                            break compare;
                        }
                    }
                }
                buf.append(c);
            }
        }
        return buf.toString();
    }

    public static String resourceprep(String resource) throws StringprepException {
        String answer = resource;
        if (!RESOURCEPREP_CACHE.contains(resource)) {
            answer = Stringprep.resourceprep(resource);
            // Validate field is not greater than 1023 bytes. UTF-8 characters use two bytes.
            if (answer != null && answer.length()*2 > 1023) {
                throw new IllegalArgumentException("Resource cannot be larger than 1023 bytes. " +
                        "Size is " + (answer.length() * 2) + " bytes.");
            }
            RESOURCEPREP_CACHE.put(answer);
        }
        return answer;
    }

    /**
     * Constructs a JID from it's String representation.
     *
     * @param jid a valid JID.
     * @throws IllegalArgumentException if the JID is not valid.
     */
    public JID(String jid) {
    	this(getParts(jid), false);
    }

    /**
	 * Constructs a JID from it's String representation. This construction
	 * allows the caller to specify if stringprep should be applied or not.
	 * 
	 * @param jid
	 *            a valid JID.
	 * @param skipStringprep
	 *            <tt>true</tt> if stringprep should not be applied.
	 * @throws IllegalArgumentException
	 *             if the JID is not valid.
	 */
    public JID(String jid, boolean skipStringPrep) {
    	this(getParts(jid), skipStringPrep);
    }
    
    private JID(String[] parts, boolean skipStringPrep) {
    	this(parts[0], parts[1], parts[2], skipStringPrep);
    }
    
    /**
     * Constructs a JID given a node, domain, and resource.
     *
     * @param node the node.
     * @param domain the domain, which must not be <tt>null</tt>.
     * @param resource the resource.
     * @throws IllegalArgumentException if the JID is not valid.
     */
    public JID(String node, String domain, String resource) {
        this(node, domain, resource, false);
    }

    /**
     * Constructs a JID given a node, domain, and resource being able to specify if stringprep
     * should be applied or not.
     *
     * @param node the node.
     * @param domain the domain, which must not be <tt>null</tt>.
     * @param resource the resource.
     * @param skipStringprep <tt>true</tt> if stringprep should not be applied.
     * @throws IllegalArgumentException if the JID is not valid.
     */
    public JID(String node, String domain, String resource, boolean skipStringprep) {
        if (domain == null) {
            throw new NullPointerException("Domain cannot be null");
        }
        if (skipStringprep) {
            this.node = node;
            this.domain = domain;
            this.resource = resource;
        }
        else {
            // Set node and resource to null if they are the empty string.
            if (node != null && node.equals("")) {
                node = null;
            }
            if (resource != null && resource.equals("")) {
                resource = null;
            }
            // Stringprep (node prep, resourceprep, etc).
            try {
                if (!NODEPREP_CACHE.contains(node)) {
                    this.node = Stringprep.nodeprep(node);
                    // Validate field is not greater than 1023 bytes. UTF-8 characters use two bytes.
                    if (this.node != null && this.node.length()*2 > 1023) {
                        throw new IllegalArgumentException("Node cannot be larger than 1023 bytes. " +
                                "Size is " + (this.node.length() * 2) + " bytes.");
                    }
                    NODEPREP_CACHE.put(this.node);
                }
                else {
                    this.node = node;
                }
                // XMPP specifies that domains should be run through IDNA and
                // that they should be run through nameprep before doing any
                // comparisons. We always run the domain through nameprep to
                // make comparisons easier later.
                if (!DOMAINPREP_CACHE.contains(domain)) {
                    this.domain = Stringprep.nameprep(IDNA.toASCII(domain), false);
                    // Validate field is not greater than 1023 bytes. UTF-8 characters use two bytes.
                    if (this.domain.length()*2 > 1023) {
                        throw new IllegalArgumentException("Domain cannot be larger than 1023 bytes. " +
                                "Size is " + (this.domain.length() * 2) + " bytes.");
                    }
                    DOMAINPREP_CACHE.put(this.domain);
                }
                else {
                    this.domain = domain;
                }
                this.resource = resourceprep(resource);
            }
            catch (Exception e) {
                StringBuilder buf = new StringBuilder();
                if (node != null) {
                    buf.append(node).append("@");
                }
                buf.append(domain);
                if (resource != null) {
                    buf.append("/").append(resource);
                }
                throw new IllegalArgumentException("Illegal JID: " + buf.toString(), e);
            }
        }
        
        // Cache the bare JID
        StringBuilder buf = new StringBuilder(40);
        if (this.node != null) {
            buf.append(this.node).append("@");
        }
        buf.append(this.domain);
        cachedBareJID = buf.toString();

        // Cache the full JID
        if (this.resource != null) {
            buf.append("/").append(this.resource);
            cachedFullJID = buf.toString();
        }
        else {
            cachedFullJID = cachedBareJID;
        }
    }

    /**
     * Returns a String array with the parsed node, domain and resource.
     * No Stringprep is performed while parsing the textual representation.
     *
     * @param jid the textual JID representation.
     * @return a string array with the parsed node, domain and resource.
     */
    static String[] getParts(String jid) {
        String[] parts = new String[3];
        String node = null , domain, resource;
        if (jid == null) {
            return parts;
        }

        int atIndex = jid.indexOf("@");
        int slashIndex = jid.indexOf("/");

        // Node
        if (atIndex > 0) {
            node = jid.substring(0, atIndex);
        }

        // Domain
        if (atIndex + 1 > jid.length()) {
            throw new IllegalArgumentException("JID with empty domain not valid");
        }
        if (atIndex < 0) {
            if (slashIndex > 0) {
                domain = jid.substring(0, slashIndex);
            }
            else {
                domain = jid;
            }
        }
        else {
            if (slashIndex > 0) {
                domain = jid.substring(atIndex + 1, slashIndex);
            }
            else {
                domain = jid.substring(atIndex + 1);
            }
        }

        // Resource
        if (slashIndex + 1 > jid.length() || slashIndex < 0) {
            resource = null;
        }
        else {
            resource = jid.substring(slashIndex + 1);
        }
        parts[0] = node;
        parts[1] = domain;
        parts[2] = resource;
        return parts;
    }

    /**
     * Returns the node, or <tt>null</tt> if this JID does not contain node information.
     *
     * @return the node.
     */
    public String getNode() {
        return node;
    }

    /**
     * Returns the domain.
     *
     * @return the domain.
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Returns the resource, or <tt>null</tt> if this JID does not contain resource information.
     *
     * @return the resource.
     */
    public String getResource() {
        return resource;
    }

    /**
     * Returns the String representation of the bare JID, which is the JID with
     * resource information removed. For example: <tt>username@domain.com</tt>
     *
     * @return the bare JID.
     */
    public String toBareJID() {
        return cachedBareJID;
    }

	/**
	 * <p>
	 * Returns the String representation of the full JID, for example:
	 * <tt>username@domain.com/mobile</tt>.
	 * </p>
	 * 
	 * <p>
	 * If no resource has been provided in the constructor of this object, an
	 * IllegalStateException is thrown.
	 * </p>
	 * 
	 * @return the full JID.
	 * @throws IllegalStateException
	 *             If no resource was provided in the constructor used to create
	 *             this instance.
	 */
	public String toFullJID() {
		if (this.resource == null) {
			throw new IllegalStateException("This JID was instantiated "
					+ "without a resource identifier. A full "
					+ "JID representation is not available for: " + toString());
		}
		return cachedFullJID;
	}
    
    /**
     * Returns a String representation of the JID.
     *
     * @return a String representation of the JID.
     */
    public String toString() {
        return cachedFullJID;
    }

    public int hashCode() {
        return cachedFullJID.hashCode();
    }

    public boolean equals(Object object) {
        if (!(object instanceof JID)) {
            return false;
        }
        if (this == object) {
            return true;
        }
        JID jid = (JID)object;
        // Node. If node isn't null, compare.
        if (node != null) {
            if (!node.equals(jid.node)) {
                return false;
            }
        }
        // Otherwise, jid.node must be null.
        else if (jid.node != null) {
            return false;
        }
        // Compare domain, which must be null.
        if (!domain.equals(jid.domain)) {
            return false;
        }
        // Resource. If resource isn't null, compare.
        if (resource != null) {
            if (!resource.equals(jid.resource)) {
                return false;
            }
        }
        // Otherwise, jid.resource must be null.
        else if (jid.resource != null) {
            return false;
        }
        // Passed all checks, so equal.
        return true;
    }

    public int compareTo(JID jid) {
        // Comparison order is domain, node, resource.
        int compare = domain.compareTo(jid.domain);
        if (compare == 0) {
            String myNode = node != null ? node : "";
            String hisNode = jid.node != null ? jid.node : "";
            compare = myNode.compareTo(hisNode);
        }
        if (compare == 0) {
            String myResource = resource != null ? resource : "";
            String hisResource = jid.resource != null ? jid.resource : "";
            compare = myResource.compareTo(hisResource);
        }
        return compare;
    }

    /**
     * Returns true if two JID's are equivalent. The JID components are compared using
     * the following rules:<ul>
     *      <li>Nodes are normalized using nodeprep (case insensitive).
     *      <li>Domains are normalized using IDNA and then nameprep (case insensitive).
     *      <li>Resources are normalized using resourceprep (case sensitive).</ul>
     *
     * These normalization rules ensure, for example, that
     * <tt>User@EXAMPLE.com/home</tt> is considered equal to <tt>user@example.com/home</tt>.
     *
     * @param jid1 a JID.
     * @param jid2 a JID.
     * @return true if the JIDs are equivalent; false otherwise.
     * @throws IllegalArgumentException if either JID is not valid.
     */
    public static boolean equals(String jid1, String jid2) {
        return new JID(jid1).equals(new JID(jid2));
    }

	/**
	 * A simple cache class with limited functionality. It uses an FIFO 
	 * eviction policy to keep the cache at a maximum size. This class
	 * offers acceptable thread safety for the purpose of the parent class.
	 * 
	 * @author Guus der Kinderen, guus@nimbuzz.com
	 */
    private static class Cache<K> {

		/** Cannot add null values in ConcurrentHashMap... */
		private final static Object NULL = new Object();

		/** Queue that records insertion order. Used to implement FIFO behavior. */
		private final Queue<K> fifoQueue = new ConcurrentLinkedQueue<K>();

		/** Values are cached in a hashmap for fast lookup. **/
		private final Map<K, Object> cachedValues = new ConcurrentHashMap<K, Object>();

		/** Cache capacity */
		private int maxSize;

		/**
		 * Constructs a new capacity-bound cache.
		 * 
		 * @param maxSize
		 *            The maximum number of elements that the cache can contain.
		 */
		public Cache(int maxSize) {
			this.maxSize = maxSize;
		}

		/**
		 * Adds a new element to the cache. The cache is pruned if the maximum
		 * capacity has been reached.
		 * 
		 * @param entry
		 *            The element to be added to the cache
		 */
		public void put(K entry) {
			synchronized (entry) {
				// add value to the cache
				if (cachedValues.put(entry, NULL) == null) {
					// ensure that queue doesn't contain duplicates.
					fifoQueue.offer(entry);
				}
			}

			// apply eviction policy if required.
			while (cachedValues.size() > maxSize) {
				cachedValues.remove(fifoQueue.poll());
			}
		}

		/**
		 * Checks if the cache contains an element.
		 * 
		 * @param entry
		 *            The element to check for.
		 * @return <tt>true</tt> if the cache currently contains the entry,
		 *         <tt>false</tt> otherwise.
		 */
		public boolean contains(K entry) {
			// no need to nodeprep null - it'll result in null.
			if (entry == null) {
				return true;
			}

			// Note that this method will need to record access if
			// we want to switch to LRU.
			return cachedValues.containsKey(entry);
		}
    }
}