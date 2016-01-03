/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.index;

import java.io.File;
import java.util.Map;
import java.util.Objects;

import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.impl.index.storage.PartitionedIndexStorage;
import org.neo4j.kernel.api.index.IndexConfiguration;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;

import static org.neo4j.helpers.collection.MapUtil.stringMap;

public class LuceneSchemaIndexBuilder
{
    private IndexSamplingConfig samplingConfig = new IndexSamplingConfig( new Config() );
    private IndexConfiguration indexConfig = IndexConfiguration.NON_UNIQUE;
    private DirectoryFactory directoryFactory = DirectoryFactory.PERSISTENT;
    private FileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction();
    private File indexRootFolder;
    private String indexIdentifier;
    private PartitionedIndexStorage indexStorage;

    private LuceneSchemaIndexBuilder()
    {
    }

    public static LuceneSchemaIndexBuilder create()
    {
        return new LuceneSchemaIndexBuilder();
    }

    public LuceneSchemaIndexBuilder withIndexIdentifier( String indexIdentifier )
    {
        this.indexIdentifier = indexIdentifier;
        return this;
    }

    public LuceneSchemaIndexBuilder withSamplingConfig( IndexSamplingConfig samplingConfig )
    {
        this.samplingConfig = samplingConfig;
        return this;
    }

    public <T> LuceneSchemaIndexBuilder withSamplingBufferSize( int size )
    {
        Map<String,String> params = stringMap( GraphDatabaseSettings.index_sampling_buffer_size.name(), size + "" );
        Config config = new Config( params );
        this.samplingConfig = new IndexSamplingConfig( config );
        return this;
    }

    public LuceneSchemaIndexBuilder withSamplingConfig( Config config )
    {
        this.samplingConfig = new IndexSamplingConfig( config );
        return this;
    }

    public LuceneSchemaIndexBuilder withIndexConfig( IndexConfiguration indexConfig )
    {
        this.indexConfig = indexConfig;
        return this;
    }

    public LuceneSchemaIndexBuilder withIndexStorage( PartitionedIndexStorage indexStorage )
    {
        this.indexStorage = indexStorage;
        return this;
    }

    public LuceneSchemaIndexBuilder uniqueIndex()
    {
        this.indexConfig = IndexConfiguration.UNIQUE;
        return this;
    }

    public LuceneSchemaIndexBuilder withDirectoryFactory( DirectoryFactory directoryFactory )
    {
        this.directoryFactory = directoryFactory;
        return this;
    }

    public LuceneSchemaIndexBuilder withFileSystem( FileSystemAbstraction fileSystem )
    {
        this.fileSystem = fileSystem;
        return this;
    }

    public LuceneSchemaIndexBuilder withIndexRootFolder( File indexRootFolder )
    {
        this.indexRootFolder = indexRootFolder;
        return this;
    }

    public LuceneSchemaIndex build()
    {
        return new LuceneSchemaIndex( buildIndexStorage(), indexConfig, samplingConfig );
    }

    private PartitionedIndexStorage buildIndexStorage()
    {
        if ( indexStorage == null )
        {
            Objects.requireNonNull( directoryFactory );
            Objects.requireNonNull( fileSystem );
            Objects.requireNonNull( indexRootFolder );
            Objects.requireNonNull( indexIdentifier );
            indexStorage =
                    new PartitionedIndexStorage( directoryFactory, fileSystem, indexRootFolder, indexIdentifier );
        }
        return indexStorage;
    }
}
