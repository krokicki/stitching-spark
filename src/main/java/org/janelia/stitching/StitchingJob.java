package org.janelia.stitching;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * @author pisarevi
 *
 */

public class StitchingJob implements Serializable {
	
	private static final long serialVersionUID = 2619120742300093982L;
	
	private StitchingArguments arguments;
	private String baseImagesFolder; 
	private TileInfo[] tiles;
	private int dimensionality;
	
	public StitchingJob( StitchingArguments arguments ) {
		this.arguments = arguments;
		baseImagesFolder = new File( arguments.getInput() ).getAbsoluteFile().getParent();
	}
	
	protected StitchingJob( ) {
		
	}
	
	public TileInfo[] getTiles() {
		return tiles;
	}
	
	public String getBaseImagesFolder() {
		return baseImagesFolder;
	}
	
	public int getDimensionality() {
		return dimensionality;
	}
	
	public void prepareTiles() throws Exception {
		loadTiles();
		validateTiles();
		
		for ( int i = 0; i < tiles.length; i++ )
			tiles[ i ].setIndex( i );
	}
	
	private void loadTiles() throws FileNotFoundException {
		final JsonReader reader = new JsonReader( new FileReader( arguments.getInput() ) );
		tiles = new Gson().fromJson( reader, TileInfo[].class );
	}
	
	public void saveTiles( final TileInfo[] resultingTiles ) throws IOException {
		final StringBuilder output = new StringBuilder( arguments.getInput() );
		int lastDotIndex = output.lastIndexOf( "." );
		if ( lastDotIndex == -1 )
			lastDotIndex = output.length();
		output.insert( lastDotIndex, "_output" );
		
		final FileWriter writer = new FileWriter( output.toString() );
		writer.write( new Gson().toJson( resultingTiles ) );
		writer.close();
	}
	
	private void validateTiles() throws IllegalArgumentException {
		if ( tiles.length < 2 )
			throw new IllegalArgumentException( "There must be at least 2 tiles in the dataset" );

		for ( int i = 0; i < tiles.length; i++ )
			if ( tiles[ i ].getPosition().length != tiles[ i ].getSize().length )
				throw new IllegalArgumentException( "Incorrect dimensionality" );
		
		for ( int i = 1; i < tiles.length; i++ )
			if ( tiles[ i ].getDimensionality() != tiles[ i - 1 ].getDimensionality() )
				throw new IllegalArgumentException( "Incorrect dimensionality" );
		
		// Everything is correct
		this.dimensionality = tiles[ 0 ].getDimensionality();
	}
}
