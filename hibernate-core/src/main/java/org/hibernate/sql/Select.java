/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.sql;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.dialect.Dialect;
import org.hibernate.internal.util.StringHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * A simple SQL <tt>SELECT</tt> statement
 * @author Gavin King
 */
public class Select {

    protected String selectClause;
    protected String fromClause;
    protected String outerJoinsAfterFrom;
    protected String whereClause;
    protected String outerJoinsAfterWhere;
    protected String orderByClause;
    protected String groupByClause;
    protected String comment;

    protected LockOptions lockOptions = new LockOptions();

    public final Dialect dialect;

    private int guesstimatedBufferSize = 20;

    public Select(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Construct an SQL <tt>SELECT</tt> statement from the given clauses
     */
    public String toStatementString() {
        StringBuilder buf = new StringBuilder(guesstimatedBufferSize);
        if (StringHelper.isNotEmpty(comment)) {
            buf.append("/* ").append(Dialect.escapeComment(comment)).append(" */ ");
        }

        buf.append("select ").append(selectClause)
                .append(" from ");

        String optimizedJoins = optimizeJoins(fromClause, outerJoinsAfterFrom);
        if (optimizedJoins != null) {
            buf.append(optimizedJoins);
        } else {
            buf.append(fromClause);
            if (StringHelper.isNotEmpty(outerJoinsAfterFrom)) {
                buf.append(outerJoinsAfterFrom);
            }
        }


        if (StringHelper.isNotEmpty(whereClause) || StringHelper.isNotEmpty(outerJoinsAfterWhere)) {
            buf.append(" where ");
            // the outerJoinsAfterWhere needs to come before where clause to properly
            // handle dynamic filters
            if (StringHelper.isNotEmpty(outerJoinsAfterWhere)) {
                buf.append(outerJoinsAfterWhere);
                if (StringHelper.isNotEmpty(whereClause)) {
                    buf.append(" and ");
                }
            }
            if (StringHelper.isNotEmpty(whereClause)) {
                buf.append(whereClause);
            }
        }

        if (StringHelper.isNotEmpty(groupByClause)) {
            buf.append(" group by ").append(groupByClause);
        }

        if (StringHelper.isNotEmpty(orderByClause)) {
            buf.append(" order by ").append(orderByClause);
        }

        if (lockOptions.getLockMode() != LockMode.NONE) {
            buf.append(dialect.getForUpdateString(lockOptions));
        }

        return dialect.transformSelectString(buf.toString());
    }

    static String optimizeJoins(String fromClause, String outerJoinsAfterFrom) {
        if (outerJoinsAfterFrom == null || !outerJoinsAfterFrom.contains("inner join"))
            return null;

        String allJoins = fromClause + " " + outerJoinsAfterFrom;
        JoinStructure joinStructure = parseJoins(allJoins.trim());
        if (joinStructure == null)
            return null;
        List<JoinEntry> originalOrder = new ArrayList<JoinEntry>(joinStructure.entries);
        Map<String, JoinEntry> aliasMap = new HashMap<String, JoinEntry>();
        for (JoinEntry joinEntry : originalOrder)
            aliasMap.put(joinEntry.tableAlias, joinEntry);
        for (JoinEntry joinEntry : originalOrder) {
            if (!joinEntry.isInnerJoin())
                continue;
            if (joinEntry.getOtherSideAlias().equals(joinStructure.mainTableAlias)) {
                joinStructure.entries.remove(joinEntry);
                joinStructure.entries.add(0, joinEntry);
                continue;
            }
            JoinEntry otherSide = aliasMap.get(joinEntry.getOtherSideAlias());
            int otherSideIndex = joinStructure.entries.indexOf(otherSide);
            if (otherSideIndex <= joinStructure.entries.indexOf(joinEntry) - 1)
                continue;
            joinStructure.entries.remove(joinEntry);
            joinStructure.entries.add(otherSideIndex + 1, joinEntry);
        }

        return joinStructure.toString();
    }

    private static class JoinStructure {
        String mainTable;
        String mainTableAlias;
        List<JoinEntry> entries = new ArrayList<JoinEntry>();

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(mainTable).append(" ").append(mainTableAlias);
            for (JoinEntry entry : entries) {
                sb.append(" ");
                sb.append(entry.joinType);
                sb.append(" join ");
                sb.append(entry.table);
                sb.append(" ");
                sb.append(entry.tableAlias);
                sb.append(" on ");
                sb.append(entry.aSideTable);
                sb.append(".");
                sb.append(entry.aSideColumn);
                sb.append("=");
                sb.append(entry.bSideTable);
                sb.append(".");
                sb.append(entry.bSideColumn);
            }
            return sb.toString();
        }
    }

    private static class JoinEntry {
        String table;
        String tableAlias;
        String joinType;
        String aSideTable;
        String aSideColumn;
        String bSideTable;
        String bSideColumn;

        boolean isInnerJoin() {
            return joinType.equals("inner");
        }

        String getOtherSideAlias() {
            if (tableAlias.equals(aSideTable))
                return bSideTable;
            return aSideTable;
        }
    }

    private static JoinStructure parseJoins(String allJoins) {
        Pattern startPattern = Pattern.compile("^(\\w+)\\s*(\\w+)");
        Matcher matcher = startPattern.matcher(allJoins);
        if (!matcher.find())
            return null;
        JoinStructure structure = new JoinStructure();

        structure.mainTable = matcher.group(1);
        structure.mainTableAlias = matcher.group(2);
        String pureJoins = allJoins.substring(matcher.end() + 1);
        Matcher joinsMatcher = Pattern.compile("\\s*(inner|left|left outer) join\\s+(\\w+)\\s+(\\w+)\\s+on\\s+(\\w+)\\s*\\.\\s*(\\w+)=\\s*(\\w+)\\s*\\.\\s*(\\w+)\\s*").matcher(pureJoins);
        int lastStart = 0;
        while (joinsMatcher.find()) {
            if (joinsMatcher.start() > lastStart)
                return null;
            lastStart = joinsMatcher.end();
            JoinEntry entry = new JoinEntry();
            entry.joinType = joinsMatcher.group(1);
            entry.table = joinsMatcher.group(2);
            entry.tableAlias = joinsMatcher.group(3);
            entry.aSideTable = joinsMatcher.group(4);
            entry.aSideColumn = joinsMatcher.group(5);
            entry.bSideTable = joinsMatcher.group(6);
            entry.bSideColumn = joinsMatcher.group(7);
            structure.entries.add(entry);
        }
        return structure;
    }

    /**
     * Sets the fromClause.
     * @param fromClause The fromClause to set
     */
    public Select setFromClause(String fromClause) {
        this.fromClause = fromClause;
        this.guesstimatedBufferSize += fromClause.length();
        return this;
    }

    public Select setFromClause(String tableName, String alias) {
        this.fromClause = tableName + ' ' + alias;
        this.guesstimatedBufferSize += fromClause.length();
        return this;
    }

    public Select setOrderByClause(String orderByClause) {
        this.orderByClause = orderByClause;
        this.guesstimatedBufferSize += orderByClause.length();
        return this;
    }

    public Select setGroupByClause(String groupByClause) {
        this.groupByClause = groupByClause;
        this.guesstimatedBufferSize += groupByClause.length();
        return this;
    }

    public Select setOuterJoins(String outerJoinsAfterFrom, String outerJoinsAfterWhere) {
        this.outerJoinsAfterFrom = outerJoinsAfterFrom;

        // strip off any leading 'and' token
        String tmpOuterJoinsAfterWhere = outerJoinsAfterWhere.trim();
        if (tmpOuterJoinsAfterWhere.startsWith("and")) {
            tmpOuterJoinsAfterWhere = tmpOuterJoinsAfterWhere.substring(4);
        }
        this.outerJoinsAfterWhere = tmpOuterJoinsAfterWhere;

        this.guesstimatedBufferSize += outerJoinsAfterFrom.length() + outerJoinsAfterWhere.length();
        return this;
    }


    /**
     * Sets the selectClause.
     * @param selectClause The selectClause to set
     */
    public Select setSelectClause(String selectClause) {
        this.selectClause = selectClause;
        this.guesstimatedBufferSize += selectClause.length();
        return this;
    }

    public Select setSelectClause(SelectFragment selectFragment) {
        setSelectClause(selectFragment.toFragmentString().substring(2));
        return this;
    }

    /**
     * Sets the whereClause.
     * @param whereClause The whereClause to set
     */
    public Select setWhereClause(String whereClause) {
        this.whereClause = whereClause;
        this.guesstimatedBufferSize += whereClause.length();
        return this;
    }

    public Select setComment(String comment) {
        this.comment = comment;
        this.guesstimatedBufferSize += comment.length();
        return this;
    }

    /**
     * Get the current lock mode
     * @return LockMode
     * @deprecated Instead use getLockOptions
     */
    @Deprecated
    public LockMode getLockMode() {
        return lockOptions.getLockMode();
    }

    /**
     * Set the lock mode
     * @param lockMode
     * @return this object
     * @deprecated Instead use setLockOptions
     */
    @Deprecated
    public Select setLockMode(LockMode lockMode) {
        lockOptions.setLockMode(lockMode);
        return this;
    }

    /**
     * Get the current lock options
     * @return LockOptions
     */
    public LockOptions getLockOptions() {
        return lockOptions;
    }

    /**
     * Set the lock options
     * @param lockOptions
     * @return this object
     */
    public Select setLockOptions(LockOptions lockOptions) {
        LockOptions.copy(lockOptions, this.lockOptions);
        return this;
    }
}
