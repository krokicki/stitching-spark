package org.janelia.stitching;

import java.util.Arrays;
import java.util.List;

import org.janelia.flatfield.FlatfieldCorrectedRandomAccessible;

import bdv.export.Downsample;
import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AbstractTranslation;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.IntervalsNullable;
import net.imglib2.view.RandomAccessiblePair;
import net.imglib2.view.RandomAccessiblePairNullable;
import net.imglib2.view.Views;

public class FusionPerformer
{
	public static <
		T extends RealType< T > & NativeType< T >,
		U extends RealType< U > & NativeType< U > >
	ImagePlusImg< FloatType, ? > fuseTilesWithinCellUsingMaxMinDistance(
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval ) throws Exception
	{
		return fuseTilesWithinCellUsingMaxMinDistance( tilesWithinCell, targetInterval, null );
	}

	/**
	 * Fuses a collection of {@link TileInfo} objects within the given target interval using hard-cut strategy in overlaps.
	 *
	 * @param tilesWithinCell
	 * 			A list of tiles that have non-empty intersection with the target interval
	 * @param targetInterval
	 * 			An output fusion cell
	 * @param interpolatorFactory
	 * 			An interpolation strategy for tiles with real coordinates
	 * @param flatfieldCorrection
	 * 			Optional flatfield correction coefficients (can be null)
	 */
	public static <
		T extends RealType< T > & NativeType< T >,
		U extends RealType< U > & NativeType< U > >
	ImagePlusImg< FloatType, ? > fuseTilesWithinCellUsingMaxMinDistance(
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval,
			final RandomAccessiblePairNullable< U, U > flatfield ) throws Exception
	{
		final ImageType imageType = Utils.getImageType( tilesWithinCell );
		if ( imageType == null )
			throw new Exception( "Can't fuse images of different or unknown types" );

		final ImagePlusImg< FloatType, ? > out = ImagePlusImgs.floats( Intervals.dimensionsAsLongArray( targetInterval ) );
		final ArrayImg< DoubleType, DoubleArray > maxMinDistances = ArrayImgs.doubles(
				Intervals.dimensionsAsLongArray( targetInterval ) );

		for ( final TileInfo tile : tilesWithinCell )
		{
			System.out.println( "Loading tile image " + tile.getFilePath() );

			final ImagePlus imp = IJ.openImage( tile.getFilePath() );
			Utils.workaroundImagePlusNSlices( imp );

			final FinalRealInterval intersection = IntervalsNullable.intersectReal(
					new FinalRealInterval( tile.getPosition(), tile.getMax() ),
					targetInterval );

			if ( intersection == null )
				throw new IllegalArgumentException( "tilesWithinCell contains a tile that doesn't intersect with the target interval:\n" + "Tile " + tile.getIndex() + " at " + Arrays.toString( tile.getPosition() ) + " of size " + Arrays.toString( tile.getSize() ) + "\n" + "Output cell " + " at " + Arrays.toString( Intervals.minAsIntArray( targetInterval ) ) + " of size " + Arrays.toString( Intervals.dimensionsAsIntArray( targetInterval ) ) );

			final double[] offset = new double[ targetInterval.numDimensions() ];
			final long[] minIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			final long[] maxIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			for ( int d = 0; d < minIntersectionInTargetInterval.length; ++d )
			{
				offset[ d ] = tile.getPosition( d ) - targetInterval.min( d );
				minIntersectionInTargetInterval[ d ] = ( long ) Math.floor( intersection.realMin( d ) ) - targetInterval.min( d );
				maxIntersectionInTargetInterval[ d ] = ( long ) Math.ceil ( intersection.realMax( d ) ) - targetInterval.min( d );
			}
			final Interval intersectionIntervalInTargetInterval = new FinalInterval( minIntersectionInTargetInterval, maxIntersectionInTargetInterval );
			final Translation translation = new Translation( offset );

			final RandomAccessibleInterval< T > rawTile = ImagePlusImgs.from( imp );
			final RandomAccessibleInterval< FloatType > floatTile = Converters.convert( rawTile, new RealFloatConverter<>(), new FloatType() );
			final RandomAccessible< FloatType > extendedTile = Views.extendBorder( floatTile );
			final RealRandomAccessible< FloatType > interpolatedTile = Views.interpolate( extendedTile, new NLinearInterpolatorFactory<>() );
			final RandomAccessible< FloatType > rasteredInterpolatedTile = Views.raster( RealViews.affine( interpolatedTile, translation ) );
			final RandomAccessibleInterval< FloatType > interpolatedTileInterval = Views.interval( rasteredInterpolatedTile, intersectionIntervalInTargetInterval );

			final RandomAccessibleInterval< FloatType > sourceInterval;
			if ( flatfield != null )
			{
				final RandomAccessible< U >[] flatfieldComponents = new RandomAccessible[] { flatfield.getA(), flatfield.getB() };
				final RandomAccessible< FloatType >[] adjustedFlatfieldComponents = new RandomAccessible[ flatfieldComponents.length ];
				for ( int i = 0; i < flatfieldComponents.length; ++i )
				{
					final RandomAccessible< U > flatfieldComponent = flatfieldComponents[ i ];
					final RandomAccessibleInterval< U > rawFlatfieldComponent = Views.interval( flatfieldComponent, new FinalInterval( tile.getSize() ) );
					final RandomAccessibleInterval< FloatType > floatFlatfieldComponent = Converters.convert( rawFlatfieldComponent, new RealFloatConverter<>(), new FloatType() );
					final RandomAccessible< FloatType > extendedFlatfieldComponent = Views.extendBorder( floatFlatfieldComponent );
					final RealRandomAccessible< FloatType > interpolatedFlatfieldComponent = Views.interpolate( extendedFlatfieldComponent, new NLinearInterpolatorFactory<>() );
					final RandomAccessible< FloatType > rasteredInterpolatedFlatfieldComponent = Views.raster( RealViews.affine( interpolatedFlatfieldComponent, translation ) );
					adjustedFlatfieldComponents[ i ] = Views.interval( rasteredInterpolatedFlatfieldComponent, intersectionIntervalInTargetInterval );
				}
				final RandomAccessiblePair< FloatType, FloatType > adjustedFlatfield =  new RandomAccessiblePair<>( adjustedFlatfieldComponents[ 0 ], adjustedFlatfieldComponents[ 1 ] );
				final FlatfieldCorrectedRandomAccessible< FloatType, FloatType > flatfieldCorrectedTile = new FlatfieldCorrectedRandomAccessible<>( interpolatedTileInterval, adjustedFlatfield );
				sourceInterval = Views.interval( flatfieldCorrectedTile, intersectionIntervalInTargetInterval );
			}
			else
			{
				sourceInterval = interpolatedTileInterval;
			}
			final RandomAccessibleInterval< FloatType > outInterval = Views.interval( out, intersectionIntervalInTargetInterval ) ;
			final RandomAccessibleInterval< DoubleType > maxMinDistanceInterval = Views.interval( maxMinDistances, intersectionIntervalInTargetInterval ) ;

			final Cursor< FloatType > sourceCursor = Views.flatIterable( sourceInterval ).localizingCursor();
			final Cursor< FloatType > outCursor = Views.flatIterable( outInterval ).cursor();
			final Cursor< DoubleType > maxMinDistanceCursor = Views.flatIterable( maxMinDistanceInterval ).cursor();

			while ( sourceCursor.hasNext() || outCursor.hasNext() || maxMinDistanceCursor.hasNext() )
			{
				sourceCursor.fwd();
				outCursor.fwd();
				final DoubleType maxMinDistance = maxMinDistanceCursor.next();
				double minDistance = Double.MAX_VALUE;
				for ( int d = 0; d < offset.length; ++d )
				{
					final double cursorPosition = sourceCursor.getDoublePosition( d );
					final double dx = Math.min(
							cursorPosition - offset[ d ],
							tile.getSize( d ) - 1 + offset[ d ] - cursorPosition );
					if ( dx < minDistance ) minDistance = dx;
				}
				if ( minDistance >= maxMinDistance.get() )
				{
					maxMinDistance.set( minDistance );
					outCursor.get().set( sourceCursor.get() );
				}
			}
		}

		return out;
	}


	/**
	 * Performs the fusion of a collection of {@link TileInfo} objects within specified cell.
	 * It uses simple pixel copying strategy then downsamples the resulting image.
	 */
	public static < T extends RealType< T > & NativeType< T > > ImagePlusImg< T, ? > fuseTilesWithinCellSimpleWithDownsampling(
			final List< TileInfo > tiles,
			final TileInfo cell,
			final int[] downsampleFactors ) throws Exception
	{
		final ImageType imageType = Utils.getImageType( tiles );
		if ( imageType == null )
			throw new Exception( "Can't fuse images of different or unknown types" );

		cell.setType( imageType );
		final T type = ( T ) imageType.getType();
		final ImagePlusImg< T, ? > fusedImg = fuseSimple( tiles, cell, type );

		final long[] outDimensions = new long[ cell.numDimensions() ];
		for ( int d = 0; d < outDimensions.length; d++ )
			outDimensions[ d ] = cell.getSize( d ) / downsampleFactors[ d ];

		final ImagePlusImg< T, ? > downsampledImg = new ImagePlusImgFactory< T >().create( outDimensions, type.createVariable() );
		Downsample.downsample( fusedImg, downsampledImg, downsampleFactors );

		return downsampledImg;
	}


	@SuppressWarnings( "unchecked" )
	private static < T extends RealType< T > & NativeType< T > > ImagePlusImg< T, ? > fuseSimple( final List< TileInfo > tiles, final TileInfo cell, final T type ) throws Exception
	{
		System.out.println( "Fusing tiles within cell #" + cell.getIndex() + " of size " + Arrays.toString( cell.getSize() )+"..." );

		// Create output image
		final Boundaries cellBoundaries = cell.getBoundaries();
		final ImagePlusImg< T, ? > out = new ImagePlusImgFactory< T >().create( cellBoundaries.getDimensions(), type.createVariable() );
		final RandomAccessibleInterval< T > cellImg = Views.translate( out, cellBoundaries.getMin() );

		for ( final TileInfo tile : tiles )
		{
			System.out.println( "Loading tile image " + tile.getFilePath() );

			final ImagePlus imp = IJ.openImage( tile.getFilePath() );
			Utils.workaroundImagePlusNSlices( imp );

			if ( imp == null )
				throw new Exception( "Can't open image: " + tile.getFilePath() );

			final Boundaries tileBoundaries = tile.getBoundaries();
			final FinalInterval intersection = IntervalsNullable.intersect( new FinalInterval( tileBoundaries.getMin(), tileBoundaries.getMax() ), cellImg );

			if ( intersection == null )
				throw new IllegalArgumentException( "tilesWithinCell contains a tile that doesn't intersect with the target interval" );

			final RandomAccessibleInterval< T > rawTile = ImagePlusImgs.from( imp );
			final RandomAccessibleInterval< T > correctedDimTile = rawTile.numDimensions() < cell.numDimensions() ? Views.stack( rawTile ) : rawTile;
			final RealRandomAccessible< T > interpolatedTile = Views.interpolate( Views.extendBorder( correctedDimTile ), new NLinearInterpolatorFactory<>() );

			final AbstractTranslation translation = ( tile.numDimensions() == 3 ? new Translation3D( tile.getPosition() ) : new Translation2D( tile.getPosition() ) );
			final RandomAccessible< T > translatedInterpolatedTile = RealViews.affine( interpolatedTile, translation );

			final IterableInterval< T > tileSource = Views.flatIterable( Views.interval( translatedInterpolatedTile, intersection ) );
			final IterableInterval< T > cellBox = Views.flatIterable( Views.interval( cellImg, intersection ) );

			final Cursor< T > source = tileSource.cursor();
			final Cursor< T > target = cellBox.cursor();

			while ( source.hasNext() )
				target.next().set( source.next() );
		}

		return out;
	}


	// TODO: 'channel' version + virtual image loader was needed for the Zeiss dataset
	/*@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static < T extends RealType< T > & NativeType< T > > Img< T > fuseTilesWithinCellUsingMaxMinDistance(
			final List< TileInfo > tiles,
			final Interval targetInterval,
			final InterpolatorFactory< T, RandomAccessible< T > > interpolatorFactory,
			final int channel ) throws Exception
	{
		final ImageType imageType = Utils.getImageType( tiles );
		if ( imageType == null )
			throw new Exception( "Can't fuse images of different or unknown types" );

		final ArrayImg< T, ? > out = new ArrayImgFactory< T >().create(
				Intervals.dimensionsAsLongArray( targetInterval ),
				( T )imageType.getType().createVariable() );
		final ArrayImg< DoubleType, DoubleArray > maxMinDistances = ArrayImgs.doubles(
				Intervals.dimensionsAsLongArray( targetInterval ) );

		for ( final TileInfo tile : tiles )
		{
			System.out.println( "Loading tile image " + tile.getFilePath() );

			final ImagePlus imp = ImageImporter.openImage( tile.getFilePath() );
			Utils.workaroundImagePlusNSlices( imp );

			final FinalRealInterval intersection = intersectReal(
					new FinalRealInterval( tile.getPosition(), tile.getMax() ),
					targetInterval );

			final double[] offset = new double[ targetInterval.numDimensions() ];
			final Translation translation = new Translation( targetInterval.numDimensions() );
			final long[] minIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			final long[] maxIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			for ( int d = 0; d < minIntersectionInTargetInterval.length; ++d )
			{
				final double shiftInTargetInterval = intersection.realMin( d ) - targetInterval.min( d );
				minIntersectionInTargetInterval[ d ] = ( long )Math.floor( shiftInTargetInterval );
				maxIntersectionInTargetInterval[ d ] = ( long )Math.min( Math.ceil( intersection.realMax( d ) - targetInterval.min( d ) ), targetInterval.max( d ) );
				offset[ d ] = tile.getPosition( d ) - targetInterval.min( d );
				translation.set( offset[ d ], d );
			}

			// TODO: handle other data types
			final VirtualStackImageLoader< T, ?, ? > loader = ( VirtualStackImageLoader ) VirtualStackImageLoader.createUnsignedShortInstance( imp );
			final RandomAccessibleInterval< T > inputRai = loader.getSetupImgLoader( channel ).getImage( 0 );

			final RealRandomAccessible< T > interpolatedTile = Views.interpolate( Views.extendBorder( inputRai ), interpolatorFactory );
			final RandomAccessible< T > rasteredInterpolatedTile = Views.raster( RealViews.affine( interpolatedTile, translation ) );

			final IterableInterval< T > sourceInterval =
					Views.flatIterable(
							Views.interval(
									rasteredInterpolatedTile,
									minIntersectionInTargetInterval,
									maxIntersectionInTargetInterval ) );
			final IterableInterval< T > outInterval =
					Views.flatIterable(
							Views.interval(
									out,
									minIntersectionInTargetInterval,
									maxIntersectionInTargetInterval ) );
			final IterableInterval< DoubleType > maxMinDistancesInterval =
					Views.flatIterable(
							Views.interval(
									maxMinDistances,
									minIntersectionInTargetInterval,
									maxIntersectionInTargetInterval ) );

			final Cursor< T > sourceCursor = sourceInterval.localizingCursor();
			final Cursor< T > outCursor = outInterval.cursor();
			final Cursor< DoubleType > maxMinDistanceCursor = maxMinDistancesInterval.cursor();

			while ( sourceCursor.hasNext() )
			{
				sourceCursor.fwd();
				outCursor.fwd();
				final DoubleType maxMinDistance = maxMinDistanceCursor.next();
				double minDistance = Double.MAX_VALUE;
				for ( int d = 0; d < offset.length; ++d )
				{
					final double cursorPosition = sourceCursor.getDoublePosition( d );
					final double dx = Math.min(
							cursorPosition - offset[ d ],
							tile.getSize( d ) - 1 + offset[ d ] - cursorPosition );
					if ( dx < minDistance ) minDistance = dx;
				}
				if ( minDistance >= maxMinDistance.get() )
				{
					maxMinDistance.set( minDistance );
					outCursor.get().set( sourceCursor.get() );
				}
			}
		}

		return out;
	}*/
}
