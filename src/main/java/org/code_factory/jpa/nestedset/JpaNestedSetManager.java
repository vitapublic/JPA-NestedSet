/**
 * LICENSE
 * <p>
 * This source file is subject to the MIT license that is bundled
 * with this package in the file MIT.txt.
 * It is also available through the world-wide-web at this URL:
 * http://www.opensource.org/licenses/mit-license.html
 */

package org.code_factory.jpa.nestedset;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import javax.inject.Inject;
import javax.persistence.Entity;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.code_factory.jpa.nestedset.annotations.LeftColumn;
import org.code_factory.jpa.nestedset.annotations.LevelColumn;
import org.code_factory.jpa.nestedset.annotations.RightColumn;
import org.code_factory.jpa.nestedset.annotations.RootColumn;
import org.hibernate.transform.AliasToEntityMapResultTransformer;

import net.jcip.annotations.NotThreadSafe;

/**
 * @author Roman Borschel <roman@code-factory.org>
 */
@NotThreadSafe
public class JpaNestedSetManager implements NestedSetManager {
    //private static Logger log = LoggerFactory.getLogger(JpaNestedSetManager.class.getName());
    private final EntityManager em;
    private final Map<Key, Node<?>> nodes;
    private final Map<Class<?>, Configuration> configs;
    private final Long defaultRootId = 1L;

    @Inject
    public JpaNestedSetManager(EntityManager em) {
        this.em = em;
        this.nodes = new HashMap<Key, Node<?>>();
        this.configs = new HashMap<Class<?>, Configuration>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EntityManager getEntityManager() {
        return this.em;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        this.nodes.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<Node<?>> getNodes() {
        return Collections.unmodifiableCollection(this.nodes.values());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends NodeInfo> List<Node<T>> fetchTreeAsList(Class<T> clazz) {
        return fetchTreeAsList(clazz, defaultRootId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends NodeInfo> List<Node<T>> fetchTreeAsList(Class<T> clazz, Long rootId) {
        Configuration config = getConfig(clazz);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> cq = cb.createQuery(clazz);
        Root<T> queryRoot = cq.from(clazz);
        cq.where(cb.ge(queryRoot.<Number>get(config.getLeftFieldName()), 1));
        cq.orderBy(cb.asc(queryRoot.get(config.getLeftFieldName())));
        applyRootId(clazz, cq, rootId);

        List<Node<T>> tree = new ArrayList<Node<T>>();
        for (T n : em.createQuery(cq).getResultList()) {
            tree.add(getNode(n));
        }

        buildTree(tree, 0);

        return tree;
    }

    @Override
    public <T extends NodeInfo> List<Map<String, Object>> fetchTreeAsAdjacencyList(Class<T> clazz) {
        return fetchTreeAsAdjacencyList(clazz, defaultRootId, 0);
    }

    @Override
    public <T extends NodeInfo> List<Map<String, Object>> fetchTreeAsAdjacencyList(Class<T> clazz, Long rootId) {
        return fetchTreeAsAdjacencyList(clazz, rootId, 0);
    }

    @Override
    public <T extends NodeInfo> List<Map<String, Object>> fetchTreeAsAdjacencyList(Class<T> clazz, Long rootId, int maxLevel) {
        Configuration config = getConfig(clazz);

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT node.*,")
                .append(" parent.id parent_id ")
                .append(" FROM ").append(config.getEntityName()).append(" node ")
                .append(" JOIN ").append(config.getEntityName()).append(" parent ")
                .append(" ON node.").append(config.getLeftFieldName())
                .append(" BETWEEN parent.").append(config.getLeftFieldName()).append(" AND parent.").append(config.getRightFieldName())
                .append(" WHERE node.").append(config.getLevelFieldName()).append(" = parent.").append(config.getLevelFieldName()).append(" + 1")
                .append(" AND node.").append(config.getRootIdFieldName()).append(" = ").append(rootId);

        if (maxLevel > 0) {
            sb.append(" AND node.").append(config.getLevelFieldName()).append(" < ").append(maxLevel);
        }

        Query query = em.createNativeQuery(sb.toString());

        org.hibernate.Query hibernateQuery = ((org.hibernate.jpa.HibernateQuery) query)
                .getHibernateQuery();
        hibernateQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE);

        return hibernateQuery.list();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends NodeInfo> Node<T> fetchTree(Class<T> clazz, Long rootId) {
        return fetchTreeAsList(clazz, rootId).get(0);
    }

    @Override
    public <T extends NodeInfo> Node<T> fetchTree(Class<T> clazz) {
        return fetchTree(clazz, defaultRootId);
    }

    /**
     * Establishes all parent/child/ancestor/descendant relationships of all the nodes in
     * the given list. As a result, invocations on the corresponding methods on these node
     * instances will not trigger any database queries.
     *
     * @param <T>
     * @param treeList
     * @param maxLevel
     * @return void
     */
    private <T extends NodeInfo> void buildTree(List<Node<T>> treeList, int maxLevel) {
        Node<T> rootNode = treeList.get(0);
        
        JpaNode<T> jpaRootNode = (JpaNode<T>)rootNode;
        if(null != jpaRootNode.children)
        	jpaRootNode.children.clear();

        Stack<JpaNode<T>> stack = new Stack<JpaNode<T>>();
        int level = rootNode.getLevel();

        for (Node<T> n : treeList) {
            JpaNode<T> node = (JpaNode<T>) n;

            if(null != node.children)
                node.children.clear();

            if (node.getLevel() < level) {
                stack.pop();
            }
            level = node.getLevel();

            if (node != rootNode) {
                JpaNode<T> parent = stack.peek();
                // set parent
                node.internalSetParent(parent);
                // add child to parent
                parent.internalAddChild(node);
                // set ancestors
                node.internalSetAncestors(new ArrayList<Node<T>>(stack));
                // add descendant to all ancestors
                for (JpaNode<T> anc : stack) {
                    anc.internalAddDescendant(node);
                }
            }

            if (node.hasChildren() && (maxLevel == 0 || maxLevel > level)) {
                stack.push(node);
            }

        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends NodeInfo> Node<T> createRoot(T root) {
        Configuration config = getConfig(root.getClass());

        int maximumRight;
        if (config.hasManyRoots()) {
            maximumRight = 0;
        } else {
            maximumRight = getMaximumRight(root.getClass());
        }
        root.setLeftValue(maximumRight + 1);
        root.setRightValue(maximumRight + 2);
        root.setLevel(0);
        if (root.getRootValue() == null) {
            root.setRootValue(defaultRootId);
        }
        em.persist(root);

		//We need to set the root.root as root.id
		root.setRootValue(root.getId());
		em.persist(root);

        return getNode(root);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends NodeInfo> Node<T> getNode(T nodeInfo) {
        Key key = new Key(nodeInfo.getClass(), nodeInfo.getId());
        if (this.nodes.containsKey(key)) {
            @SuppressWarnings("unchecked")
            Node<T> n = (Node<T>) this.nodes.get(key);
            return n;
        }
        Node<T> node = new JpaNode<T>(nodeInfo, this);
        if (!node.isValid()) {
            throw new IllegalArgumentException("The given NodeInfo instance has no position " +
                    "in a tree and is thus not yet a node.");
        }
        this.nodes.put(key, node);

        return node;
    }

    /**
     * INTERNAL:
     * Gets the nestedset configuration for the given class.
     *
     * @param clazz
     * @return The configuration.
     */
    Configuration getConfig(Class<?> clazz) {
        if (!this.configs.containsKey(clazz)) {
            Configuration config = new Configuration();

            Entity entity = clazz.getAnnotation(Entity.class);
            String name = entity.name();
            config.setEntityName((name != null && name.length() > 0) ? name : clazz.getSimpleName());

            for (Field field : clazz.getDeclaredFields()) {
                if (field.getAnnotation(LeftColumn.class) != null) {
                    config.setLeftFieldName(field.getName());
                } else if (field.getAnnotation(RightColumn.class) != null) {
                    config.setRightFieldName(field.getName());
                } else if (field.getAnnotation(LevelColumn.class) != null) {
                    config.setLevelFieldName(field.getName());
                } else if (field.getAnnotation(RootColumn.class) != null) {
                    config.setRootIdFieldName(field.getName());
                }
            }

            this.configs.put(clazz, config);
        }

        return this.configs.get(clazz);
    }

    int getMaximumRight(Class<? extends NodeInfo> clazz) {
        Configuration config = getConfig(clazz);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<? extends NodeInfo> cq = cb.createQuery(clazz);
        Root<? extends NodeInfo> queryRoot = cq.from(clazz);
        cq.orderBy(cb.desc(queryRoot.get(config.getRightFieldName())));
        List<? extends NodeInfo> highestRows = em.createQuery(cq).setMaxResults(1).getResultList();
        if (highestRows.isEmpty()) {
            return 0;
        } else {
            return highestRows.get(0).getRightValue();
        }
    }

    /**
     * INTERNAL:
     * Applies the root ID criteria to the given CriteriaQuery.
     *
     * @param cq
     * @param rootId
     */
    void applyRootId(Class<?> clazz, CriteriaQuery<?> cq, Long rootId) {
        Configuration config = getConfig(clazz);
        if (config.getRootIdFieldName() != null && rootId != null) {
            Root<?> root = cq.getRoots().iterator().next();
            CriteriaBuilder cb = em.getCriteriaBuilder();
            Predicate p = cq.getRestriction();
            cq.where(cb.and(p, cb.equal(root.get(config.getRootIdFieldName()), rootId)));
        }
    }

    /**
     * INTERNAL:
     * Updates the left values of all nodes currently known to the manager.
     *
     * @param minLeft The lower bound (inclusive) of the left values to update.
     * @param maxLeft The upper bound (inclusive) of the left values to update.
     * @param delta   The delta to apply on the left values within the range.
     */
    void updateLeftValues(int minLeft, int maxLeft, int delta, Long rootId) {
        for (Node<?> node : this.nodes.values()) {
            if (node.getRootValue() == rootId) {
                if (node.getLeftValue() >= minLeft && (maxLeft == 0 || node.getLeftValue() <= maxLeft)) {
                    node.setLeftValue(node.getLeftValue() + delta);
                    ((JpaNode<?>) node).invalidate();
                }
            }
        }
    }

    /**
     * INTERNAL:
     * Updates the right values of all nodes currently known to the manager.
     *
     * @param minRight The lower bound (inclusive) of the right values to update.
     * @param maxRight The upper bound (inclusive) of the right values to update.
     * @param delta    The delta to apply on the right values within the range.
     */
    void updateRightValues(int minRight, int maxRight, int delta, Long rootId) {
        for (Node<?> node : this.nodes.values()) {
            if (node.getRootValue() == rootId) {
                if (node.getRightValue() >= minRight && (maxRight == 0 || node.getRightValue() <= maxRight)) {
                    node.setRightValue(node.getRightValue() + delta);
                    ((JpaNode<?>) node).invalidate();
                }
            }
        }
    }

    /**
     * INTERNAL:
     * Updates the level values of all nodes currently known to the manager.
     *
     * @param left  The lower bound left value.
     * @param right The upper bound right value.
     * @param delta The delta to apply on the level values of the nodes within the range.
     */
    void updateLevels(int left, int right, int delta, Long rootId) {
        for (Node<?> node : this.nodes.values()) {
            if (node.getRootValue() == rootId) {
                if (node.getLeftValue() > left && node.getRightValue() < right) {
                    node.setLevel(node.getLevel() + delta);
                    ((JpaNode<?>) node).invalidate();
                }
            }
        }
    }

    void removeNodes(int left, int right, Long rootId) {
        Set<Key> removed = new HashSet<Key>();
        for (Node<?> node : this.nodes.values()) {
            if (node.getRootValue() == rootId) {
                if (node.getLeftValue() >= left && node.getRightValue() <= right) {
                    removed.add(new Key(node.unwrap().getClass(), node.getId()));
                }
            }
        }
        for (Key k : removed) {
            Node<?> n = this.nodes.remove(k);
            n.setLeftValue(0);
            n.setRightValue(0);
            n.setLevel(0);
            n.setRootValue(null);
            this.em.detach(n.unwrap());
        }
    }
}
