package net.morbz.osmonaut;

/*
* The MIT License (MIT)
* 
* Copyright (c) 2015 Merten Peetz
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*/

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.mapdb.DB;
import org.mapdb.DBMaker;

import net.morbz.osmonaut.binary.OsmonautSink;
import net.morbz.osmonaut.binary.pbf.PbfDecoder;
import net.morbz.osmonaut.osm.Entity;
import net.morbz.osmonaut.osm.EntityType;
import net.morbz.osmonaut.osm.Node;
import net.morbz.osmonaut.osm.Relation;
import net.morbz.osmonaut.osm.RelationMember;
import net.morbz.osmonaut.osm.Way;

/**
 * The base OSMonaut class that handles scanning of an OSM .pbf file.
 * 
 * @author MorbZ
 */
public class Osmonaut {
	private EntityCache<Node> nodeCache;
	private EntityCache<Way> wayCache;

	private final File file;
	private final EntityFilter filter;
	private IOsmonautReceiver receiver;
	private PbfDecoder decoder;

	private boolean wayNodeTags = true;
	private int processors;
	private boolean storeOnDisk = false;

	/**
	 * @param filename
	 *            The name of the .pbf file to scan
	 * @param filter
	 *            The entity filter that tells which entities should be scanned
	 */
	public Osmonaut(String filename, EntityFilter filter) {
		this.file = new File(filename);
		this.filter = filter;
		processors = Math.min(4, Runtime.getRuntime().availableProcessors());
	}

	/**
	 * @param filename
	 *            The name of the .pbf file to scan
	 * @param filter
	 *            The entity filter that tells which entities should be scanned
	 * @param wayNodeTags
	 *            Whether way-nodes should have tags. Disabling lowers memory
	 *            usage.
	 * 
	 * @deprecated Use setWayNodeTags() instead.
	 */
	@Deprecated
	public Osmonaut(String filename, EntityFilter filter, boolean wayNodeTags) {
		this(filename, filter);
		this.wayNodeTags = wayNodeTags;
	}

	/**
	 * Starts the scanning process.
	 * 
	 * @param receiver
	 *            The object that will receive the OSM entities
	 */
	public void scan(IOsmonautReceiver receiver) {
		this.receiver = receiver;

		System.out.println("OSMonaut started");

		// Check if file exists
		if (!file.exists()) {
			System.out.println("E: Input file does not exist");
			return;
		}

		// Check if there is at least 1 needed entity type
		boolean somethingNeeded = false;
		for (EntityType type : EntityType.values()) {
			if (filter.getEntityEnabled(type)) {
				somethingNeeded = true;
				break;
			}
		}
		if (!somethingNeeded) {
			System.out.println("Nothing to scan");
			return;
		}

		// Create PBF decoder
		decoder = new PbfDecoder(file, processors);

		// Create caches
		if (storeOnDisk) {
			// Create MapDB database
			DB db = DBMaker.tempFileDB().closeOnJvmShutdown().fileMmapEnableIfSupported().fileChannelEnable().make();

			nodeCache = new EntityCache<Node>(db, "node");
			wayCache = new EntityCache<Way>(db, "way");
		} else {
			nodeCache = new EntityCache<Node>();
			wayCache = new EntityCache<Way>();
		}

		// Scan relations
		if (filter.getEntityEnabled(EntityType.RELATION)) {
			System.out.println("Scanning relations...");
			scanRelations();
		}

		// Scan ways
		if (filter.getEntityEnabled(EntityType.WAY) || wayCache.needsEntities()) {
			System.out.println("Scanning ways...");
			scanWays();
		}

		// Final scan
		System.out.println("Final scan...");
		finalScan();
		
		// Close PBF file
		decoder.close();
		
		// Free variables
		nodeCache = null;
		wayCache = null;
		decoder = null;
	}

	/**
	 * This scan gets the IDs of all members of required relations.
	 */
	private void scanRelations() {
		decoder.scan(EntityType.RELATION, new OsmonautSink() {
			@Override
			public void foundEntity(Entity entity) {
				Relation relation = (Relation)entity;

				// Is needed?
				if (!entityNeededForReceiver(relation)) {
					return;
				}

				// Get all member IDs
				for (RelationMember member : relation.getMembers()) {
					// Member type
					switch (member.getEntity().getEntityType()) {
					case NODE:
						nodeCache.addNeeded(member.getEntity().getId());
						break;
					case WAY:
						wayCache.addNeeded(member.getEntity().getId());
						break;
					case RELATION:
						// TODO: Handle super-relations
						break;
					}
				}
			}
		});
	}

	/**
	 * This scan gets the IDs of all nodes of required ways.
	 */
	private void scanWays() {
		decoder.scan(EntityType.WAY, new OsmonautSink() {
			@Override
			public void foundEntity(Entity entity) {
				Way way = (Way)entity;

				// Is needed?
				if (!entityNeededForReceiver(way) && !wayCache.isNeeded(way.getId())) {
					return;
				}

				// Add all node IDs
				for (Node node : way.getNodes()) {
					nodeCache.addNeeded(node.getId());
				}
			}
		});
	}

	/**
	 * This scan is executed when all required nodes and ways are cached.
	 */
	private void finalScan() {
		if(filter.getEntityEnabled(EntityType.NODE) || nodeCache.needsEntities()) {
			System.out.println("...Scanning nodes");
			decoder.scan(EntityType.NODE, new OsmonautSink() {
				@Override
				public void foundEntity(Entity entity) {
					Node node = (Node)entity;

					// Is needed by receiver?
					if (entityNeededForReceiver(node)) {
						receiver.foundEntity(node);
					}

					// Is needed for ways/relations?
					if (nodeCache.isNeeded(node.getId())) {
						if (!wayNodeTags) {
							// Remove tags
							node = new Node(node.getId(), null, node.getLatlon());
						}
						nodeCache.addEntity(node);
					}
				}
			});
		}

		if(filter.getEntityEnabled(EntityType.WAY) || wayCache.needsEntities()) {
			System.out.println("...Scanning ways");
			decoder.scan(EntityType.WAY, new OsmonautSink() {
				@Override
				public void foundEntity(Entity entity) {
					Way way = (Way)entity;

					// Is needed?
					if (!entityNeededForReceiver(way) && !wayCache.isNeeded(way.getId())) {
						return;
					}

					// Assemble nodes
					List<Node> nodes = new ArrayList<Node>();
					for (Node incompleteNode : way.getNodes()) {
						Node node = nodeCache.getEntity(incompleteNode.getId());
						if (node == null) {
							System.out.println("E: Node for way not found");
						} else {
							nodes.add(node);
						}
					}

					// Assemble way
					Way newWay = new Way(way.getId(), way.getTags(), nodes);

					// Is needed by receiver?
					if (entityNeededForReceiver(way)) {
						receiver.foundEntity(newWay);
					}

					// Is needed for relations?
					if (wayCache.isNeeded(way.getId())) {
						wayCache.addEntity(newWay);
					}
				}
			});
		}

		if(filter.getEntityEnabled(EntityType.RELATION)) {
			System.out.println("...Scanning relations");
			decoder.scan(EntityType.RELATION, new OsmonautSink() {
				@Override
				public void foundEntity(Entity entity) {
					Relation relation = (Relation)entity;

					// Is needed?
					if (!entityNeededForReceiver(relation)) {
						return;
					}

					// Assemble members
					boolean incomplete = relation.isIncomplete();
					List<RelationMember> members = new ArrayList<RelationMember>();
					for (RelationMember member : relation.getMembers()) {
						// Get real entity
						long id = member.getEntity().getId();
						Entity memberEntity = null;
						switch (member.getEntity().getEntityType()) {
						case NODE:
							memberEntity = nodeCache.getEntity(id);
							break;
						case WAY:
							memberEntity = wayCache.getEntity(id);
							break;
						default:
							break;
						}

						// Add entity
						if (memberEntity == null) {
							// System.out.println("E: Missing relation member");
							incomplete = true;
						} else {
							members.add(new RelationMember(memberEntity, member.getRole()));
						}
					}

					// Assemble relation
					Relation newRelation = new Relation(relation.getId(), relation.getTags(), members, incomplete);
					receiver.foundEntity(newRelation);
				}
			});
		}
	}

	/**
	 * Checks if the receiver needs this entity type in general and also exactly
	 * this entity.
	 * 
	 * @param entity
	 *            The entity to check
	 * @return True if the receiver needs this entity
	 */
	private boolean entityNeededForReceiver(Entity entity) {
		EntityType type = entity.getEntityType();
		if (!filter.getEntityEnabled(type)) {
			return false;
		}
		if (!receiver.needsEntity(type, entity.getTags())) {
			return false;
		}
		return true;
	}

	/* Settings */
	/**
	 * @param wayNodeTags
	 *            Whether way-nodes should have tags. Disabling lowers memory
	 *            usage. Defaults to 'true'.
	 */
	public void setWayNodeTags(boolean wayNodeTags) {
		this.wayNodeTags = wayNodeTags;
	}

	/**
	 * @param processors
	 *            Number of processors to use to decode the pbf. By default all
	 *            available processors are used.
	 */
	public void setProcessors(int processors) {
		this.processors = processors;
	}

	/**
	 * @param storeOnDisk
	 *            Whether entity caches should be stored on disk. Enabling this
	 *            leads to lower memory usage but takes more time. Defaults to
	 *            'false'.
	 */
	public void setStoreOnDisk(boolean storeOnDisk) {
		this.storeOnDisk = storeOnDisk;
	}
}