package org.janelia.flatfield;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.dataaccess.PathResolver;
import org.janelia.flatfield.FlatfieldCorrectionSolver.FlatfieldRegularizerMetadata;
import org.janelia.flatfield.FlatfieldCorrectionSolver.FlatfieldRegularizerMetadata.RegularizerMode;
import org.janelia.flatfield.FlatfieldCorrectionSolver.FlatfieldSolutionMetadata;
import org.janelia.flatfield.FlatfieldCorrectionSolver.ModelType;
import org.janelia.flatfield.FlatfieldCorrectionSolver.RegularizerModelType;
import org.janelia.stitching.TileInfo;
import org.janelia.stitching.TileInfoJSONProvider;
import org.janelia.stitching.Utils;
import org.kohsuke.args4j.CmdLineException;

import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.histogram.Real1dBinMapper;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineSet;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.RandomAccessiblePairNullable;
import net.imglib2.view.Views;
import scala.Tuple2;

public class FlatfieldCorrection implements Serializable, AutoCloseable
{
	private static final long serialVersionUID = -8987192045944606043L;

	public static final String flatfieldFolderSuffix = "-flatfield";
	public static final String scalingTermFilename = "S.tif";
	public static final String translationTermFilename = "T.tif";

	private static final int SCALE_LEVEL_MIN_PIXELS = 1;
	private static final int AVERAGE_SKIP_SLICES = 5;

	private final String basePath, solutionPath;

	private transient final JavaSparkContext sparkContext;
	private transient final TileInfo[] tiles;

	private final DataProvider dataProvider;

	private final long[] fullTileSize;
	private final Interval workingInterval;

	private final FlatfieldCorrectionArguments args;

	public static void main( final String[] args ) throws CmdLineException, IOException, URISyntaxException
	{
		final FlatfieldCorrectionArguments argsParsed = new FlatfieldCorrectionArguments( args );
		if ( !argsParsed.parsedSuccessfully() )
			System.exit( 1 );

		try ( final FlatfieldCorrection driver = new FlatfieldCorrection( argsParsed ) )
		{
			driver.run();
		}
		System.out.println("Done");
	}


	public static < U extends NativeType< U > & RealType< U > > RandomAccessiblePairNullable< U, U > loadCorrectionImages(
			final DataProvider dataProvider,
			final String basePath,
			final int dimensionality ) throws IOException
	{
		final String scalingTermPath = PathResolver.get( basePath + flatfieldFolderSuffix, scalingTermFilename );
		final String translationTermPath = PathResolver.get( basePath + flatfieldFolderSuffix, translationTermFilename );

		System.out.println( "Loading flat-field components:" );
		System.out.println( "  " + scalingTermPath );
		System.out.println( "  " + translationTermPath );

		if ( !dataProvider.fileExists( scalingTermPath ) || !dataProvider.fileExists( translationTermPath ) )
		{
			System.out.println( "  -- Flat-field images do not exist" );
			return null;
		}

		final ImagePlus scalingTermImp = dataProvider.loadImage( scalingTermPath );
		final ImagePlus translationTermImp = dataProvider.loadImage( translationTermPath );

		final RandomAccessibleInterval< U > scalingTermImg = ImagePlusImgs.from( scalingTermImp );
		final RandomAccessibleInterval< U > translationTermImg = ImagePlusImgs.from( translationTermImp );

		final RandomAccessible< U > scalingTermImgExtended = ( scalingTermImg.numDimensions() < dimensionality ? Views.extendBorder( Views.stack( scalingTermImg ) ) : scalingTermImg );
		final RandomAccessible< U > translationTermImgExtended = ( translationTermImg.numDimensions() < dimensionality ? Views.extendBorder( Views.stack( translationTermImg ) ) : translationTermImg );

		return new RandomAccessiblePairNullable<>( scalingTermImgExtended, translationTermImgExtended );
	}

	public static <
		T extends NativeType< T > & RealType< T >,
		U extends NativeType< U > & RealType< U > >
	ImagePlusImg< FloatType, ? > applyCorrection( final RandomAccessibleInterval< T > src, final RandomAccessiblePairNullable< U, U > correction )
	{
		final ImagePlusImg< FloatType, ? > dst = ImagePlusImgs.floats( Intervals.dimensionsAsLongArray( src ) );
		final Cursor< T > srcCursor = Views.flatIterable( src ).localizingCursor();
		final Cursor< FloatType > dstCursor = Views.flatIterable( Views.translate( dst, Intervals.minAsLongArray( src ) ) ).cursor();
		final RandomAccessiblePairNullable< U, U >.RandomAccess correctionRandomAccess = correction.randomAccess();
		while ( srcCursor.hasNext() || dstCursor.hasNext() )
		{
			srcCursor.fwd();
			correctionRandomAccess.setPosition( srcCursor );
			dstCursor.next().setReal( srcCursor.get().getRealDouble() * correctionRandomAccess.getA().getRealDouble() + correctionRandomAccess.getB().getRealDouble() );
		}
		return dst;
	}



	public FlatfieldCorrection( final FlatfieldCorrectionArguments args ) throws IOException
	{
		this.args = args;

		this.dataProvider = DataProviderFactory.create( DataProviderFactory.detectType( args.inputFilePath() ) );

		tiles = TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( args.inputFilePath() ) );
		fullTileSize = getMinTileSize( tiles );
		workingInterval = args.cropMinMaxInterval( args.use2D() ? new long[] { fullTileSize[ 0 ], fullTileSize[ 1 ] } : fullTileSize );

		System.out.println( "Working interval is at " + Arrays.toString( Intervals.minAsLongArray( workingInterval ) ) + " of size " + Arrays.toString( Intervals.dimensionsAsLongArray( workingInterval ) ) );

		basePath = args.inputFilePath().substring( 0, args.inputFilePath().lastIndexOf( "." ) ) + flatfieldFolderSuffix;
		solutionPath = PathResolver.get( basePath, args.cropMinMaxIntervalStr() == null ? "fullsize" : args.cropMinMaxIntervalStr(), "solution" );

		checkSameSizeForAllTiles();

		sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "FlatfieldCorrection" )
				//.set( "spark.driver.maxResultSize", "8g" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
				//.set( "spark.kryoserializer.buffer.max", "2047m" )
				.registerKryoClasses( new Class[] { Integer.class, Long.class, float[].class, Short.class, Double.class, TreeMap.class, TreeMap[].class, long[].class, short[][].class, double[].class, List.class, Tuple2.class, Interval.class, FinalInterval.class, ArrayImg.class, DoubleType.class, DoubleArray.class } )
				.set( "spark.rdd.compress", "true" )
				//.set( "spark.executor.heartbeatInterval", "10000000" )
				//.set( "spark.network.timeout", "10000000" )
				//.set( "spark.locality.wait", "0" )
			);
	}

	@Override
	public void close()
	{
		if ( sparkContext != null )
			sparkContext.close();
	}


	private void checkSameSizeForAllTiles()
	{
		// make sure that all tiles are of the same size
		for ( final TileInfo tile : tiles )
		{
			for ( int d = 0; d < tile.numDimensions(); d++ )
			{
				if ( tile.getSize(d) != fullTileSize[ d ] )
				{
					System.out.println("Assumption failed: not all the tiles are of the same size");
					System.exit(1);
				}
			}
		}
	}


	public < A extends AffineGet & AffineSet, T extends NativeType< T > & RealType< T >, V extends TreeMap< Short, Integer > >
	void run() throws IOException, URISyntaxException
	{
		long elapsed = System.nanoTime();

		System.out.println( "Working with stack of size " + tiles.length );
		System.out.println( "Output directory: " + solutionPath );

		System.out.println( "Running flatfield correction script in " + ( args.use2D() ? "2D" : "3D" ) + " mode" );

		final HistogramSettings histogramSettings;
		final Double pivotValue;
		if ( !args.getHistogramSettings().isValid() || args.pivotValue() == null )
		{
			final StackHistogram stackHistogram = StackHistogram.getStackHistogram( sparkContext, tiles );
			final Pair< Double, Double > intensityRange = stackHistogram.getIntensityRange( args.getMinMaxQuantiles() );
			histogramSettings = new HistogramSettings( Math.floor( intensityRange.getA() ), Math.ceil( intensityRange.getB() ), args.getHistogramSettings().bins );
			pivotValue = ( double ) Math.round( stackHistogram.getPivotValue() );
		}
		else
		{
			histogramSettings = args.getHistogramSettings();
			pivotValue = args.pivotValue();
		}

		System.out.println( "Histogram intensity range: min=" + histogramSettings.histMinValue + ", max=" + histogramSettings.histMaxValue );
		System.out.println( "Pivot value: " + pivotValue );

		final HistogramsProvider histogramsProvider = new HistogramsProvider(
				sparkContext,
				dataProvider,
				workingInterval,
				basePath,
				tiles,
				fullTileSize,
				histogramSettings
			);

		final double[] referenceHistogram = histogramsProvider.getReferenceHistogram();
		System.out.println( "Obtained reference histogram of size " + referenceHistogram.length + " (first and last bins are tail bins)" );
		System.out.println( "Reference histogram:");
		System.out.println( Arrays.toString( referenceHistogram ) );
		System.out.println();

		// save the reference histogram to file so one can plot it
		final String referenceHistogramFilepath = PathResolver.get( basePath, "referenceHistogram-min_" + histogramSettings.histMinValue + ",max_" + histogramSettings.histMaxValue + ".txt" );
		try ( final OutputStream out = dataProvider.getOutputStream( referenceHistogramFilepath ) )
		{
			final Real1dBinMapper< DoubleType > binMapper = new Real1dBinMapper<>(
					histogramSettings.histMinValue,
					histogramSettings.histMaxValue,
					histogramSettings.bins,
					true
				);
			final DoubleType binCenterValue = new DoubleType();
			try ( final PrintWriter writer = new PrintWriter( out ) )
			{
				writer.println( "BinValue Frequency");
				for ( int bin = 1; bin < referenceHistogram.length - 1; ++bin )
				{
					binMapper.getCenterValue( bin, binCenterValue );
					writer.println( binCenterValue.get() + " " + referenceHistogram[ bin ] );
				}
			}
		}

		// log estimated pivot point value
		final String pivotPointLogFilepath = PathResolver.get( basePath, "pivotPoint_" + pivotValue );
		try ( final OutputStream out = dataProvider.getOutputStream( pivotPointLogFilepath ) ) { }

		// Generate downsampled histograms with half-pixel offset
		final ShiftedDownsampling< A > shiftedDownsampling = new ShiftedDownsampling<>( sparkContext, histogramsProvider );
		final FlatfieldCorrectionSolver solver = new FlatfieldCorrectionSolver( sparkContext, histogramsProvider );

		final int iterations = 1;
		final int startScale = findStartingScale( shiftedDownsampling ), endScale = 0;

		FlatfieldSolutionMetadata lastSolutionMetadata = null;
		for ( int iter = 0; iter < iterations; iter++ )
		{
			FlatfieldSolutionMetadata downsampledSolutionMetadata = null;

			// solve in a bottom-up fashion (starting from the smallest scale level)
			for ( int scale = startScale; scale >= endScale; scale-- )
			{
				final ModelType modelType;
				final RegularizerModelType regularizerModelType;

				modelType = scale >= Math.round( ( double ) ( startScale + endScale ) / 2 ) ? ModelType.AffineModel : ModelType.FixedScalingAffineModel;
				regularizerModelType = iter == 0 && scale == startScale ? RegularizerModelType.IdentityModel : RegularizerModelType.AffineModel;

				final FlatfieldRegularizerMetadata regularizerMetadata;
				if ( regularizerModelType == RegularizerModelType.AffineModel )
				{
					final String scalingRegularizerDataset, translationRegularizerDataset;
					final RegularizerMode scalingRegularizerMode, translationRegularizerMode;

					if ( modelType != ModelType.FixedScalingAffineModel || lastSolutionMetadata == null )
					{
						scalingRegularizerDataset = downsampledSolutionMetadata != null ? downsampledSolutionMetadata.scalingTermDataset : null;
						scalingRegularizerMode = RegularizerMode.UPSAMPLE_CURRENT_SOLUTION;
					}
					else
					{
						scalingRegularizerDataset = lastSolutionMetadata.scalingTermDataset;
						scalingRegularizerMode = RegularizerMode.DOWNSAMPLE_PREVIOUS_SOLUTION;
					}

					if ( modelType != ModelType.FixedTranslationAffineModel || lastSolutionMetadata == null )
					{
						translationRegularizerDataset = downsampledSolutionMetadata != null ? downsampledSolutionMetadata.translationTermDataset : null;
						translationRegularizerMode = RegularizerMode.UPSAMPLE_CURRENT_SOLUTION;
					}
					else
					{
						translationRegularizerDataset = lastSolutionMetadata.translationTermDataset;
						translationRegularizerMode = RegularizerMode.DOWNSAMPLE_PREVIOUS_SOLUTION;
					}

					regularizerMetadata = new FlatfieldRegularizerMetadata(
							scalingRegularizerDataset, translationRegularizerDataset,
							scalingRegularizerMode, translationRegularizerMode
						);
				}
				else
				{
					regularizerMetadata = null;
				}

				System.out.println( "Solving for scale " + scale + ":  size=" + Arrays.toString( shiftedDownsampling.getDimensionsAtScale( scale ) ) + ",  model=" + modelType.toString() + ", regularizer=" + regularizerModelType.toString() );

				final FlatfieldSolutionMetadata currentSolutionMetadata = solver.leastSquaresInterpolationFit(
						scale,
						shiftedDownsampling.getDatasetAtScale( scale ),
						regularizerMetadata,
						shiftedDownsampling,
						modelType,
						regularizerModelType,
						histogramSettings,
						pivotValue
					);

				// keep older scale of the fixed-component solution to avoid unnecessary chain of upscaling operations which reduces contrast
				if ( scale != endScale )
				{
					switch ( modelType )
					{
					case FixedScalingAffineModel:
						downsampledSolutionMetadata = new FlatfieldSolutionMetadata(
								downsampledSolutionMetadata.scalingTermDataset,
								currentSolutionMetadata.translationTermDataset,
								pivotValue
							);
						break;
					case FixedTranslationAffineModel:
						downsampledSolutionMetadata = new FlatfieldSolutionMetadata(
								currentSolutionMetadata.scalingTermDataset,
								downsampledSolutionMetadata.translationTermDataset,
								pivotValue
							);
						break;
					default:
						downsampledSolutionMetadata = currentSolutionMetadata;
						break;
					}
				}
				else
				{
					downsampledSolutionMetadata = currentSolutionMetadata;
				}
			}

			lastSolutionMetadata = downsampledSolutionMetadata;

			/*if ( iter % 2 == 0 && lastSolution.getB().numDimensions() > 2 )
			{
				final RandomAccessibleInterval< DoubleType > averageTranslationalComponent = averageSolutionComponent( lastSolution.getB() );
				saveSolutionComponent( dataProvider, iter, 0, averageTranslationalComponent, Utils.addFilenameSuffix( translationTermFilename, "_avg" ) );

				lastSolution = new ValuePair<>(
						lastSolution.getA(),
						Views.interval( Views.extendBorder( Views.stack( averageTranslationalComponent ) ), lastSolution.getA() ) );
			}*/
		}

		// account for the pivot point in the final solution
		final Pair< RandomAccessibleInterval< DoubleType >, RandomAccessibleInterval< DoubleType > > unpivotedSolution = FlatfieldCorrectionSolver.unpivotSolution(
				lastSolutionMetadata.open( dataProvider, histogramsProvider.getHistogramsN5BasePath() ) );

		saveSolutionComponent( dataProvider, iterations - 1, 0, unpivotedSolution.getA(), Utils.addFilenameSuffix( scalingTermFilename, "_offset" ) );
		saveSolutionComponent( dataProvider, iterations - 1, 0, unpivotedSolution.getB(), Utils.addFilenameSuffix( translationTermFilename, "_offset" ) );

		// save final solution to the main folder for this channel
		{
			final ImagePlus sImp = ImageJFunctions.wrap( unpivotedSolution.getA(), scalingTermFilename );
			final String sImpPath = PathResolver.get( basePath, scalingTermFilename );
			dataProvider.saveImage( sImp, sImpPath );
		}

		{
			final ImagePlus tImp = ImageJFunctions.wrap( unpivotedSolution.getB(), translationTermFilename );
			final String tImpPath = PathResolver.get( basePath, translationTermFilename );
			dataProvider.saveImage( tImp, tImpPath );
		}


		// cleanup intermediate flatfield correction steps image data
		// TODO: add cmd switch to disable this behavior if needed to inspect individual steps
		System.out.println( "Cleaning up temporary files..." );
		final String folderToDelete = PathResolver.getParent( solutionPath );
		dataProvider.deleteFolder( folderToDelete );

		// cleanup intermediate N5 exports
		solver.cleanupFlatfieldSolutionExports( dataProvider, histogramsProvider.getHistogramsN5BasePath() );
		shiftedDownsampling.cleanupDownsampledHistograms();

		elapsed = System.nanoTime() - elapsed;
		System.out.println( "----------" );
		System.out.println( String.format( "Took %f mins", elapsed / 1e9 / 60 ) );
	}

	private int findStartingScale( final ShiftedDownsampling< ? > shiftedDownsampling )
	{
		for ( int scale = shiftedDownsampling.getNumScales() - 1; scale >= 0; --scale )
			if ( Intervals.numElements( shiftedDownsampling.getDimensionsAtScale( scale ) ) >= SCALE_LEVEL_MIN_PIXELS )
				return scale;
		return -1;
	}


	private RandomAccessibleInterval< DoubleType > averageSolutionComponent( final RandomAccessibleInterval< DoubleType > solutionComponent )
	{
		final RandomAccessibleInterval< DoubleType > dst = ArrayImgs.doubles( new long[] { solutionComponent.dimension( 0 ), solutionComponent.dimension( 1 ) } );

		final RandomAccessibleInterval< DoubleType > src = Views.interval( solutionComponent, new FinalInterval(
				new long[] { dst.min( 0 ), dst.min( 1 ), solutionComponent.min( 2 ) + AVERAGE_SKIP_SLICES },
				new long[] { dst.max( 0 ), dst.max( 1 ), solutionComponent.max( 2 ) - AVERAGE_SKIP_SLICES } ) );

		for ( long slice = src.min( 2 ); slice <= src.max( 2 ); slice++ )
		{
			final Cursor< DoubleType > srcSliceCursor = Views.flatIterable( Views.hyperSlice( src, 2, slice ) ).cursor();
			final Cursor< DoubleType > dstCursor = Views.flatIterable( dst ).cursor();

			while ( dstCursor.hasNext() || srcSliceCursor.hasNext() )
				dstCursor.next().add( srcSliceCursor.next() );
		}

		final Cursor< DoubleType > dstCursor = Views.iterable( dst ).cursor();
		while ( dstCursor.hasNext() )
		{
			final DoubleType val = dstCursor.next();
			val.set( val.get() / src.dimension( 2 ) );
		}

		return dst;
	}


	private void saveSolution(
			final DataProvider dataProvider,
			final int iteration,
			final int scale,
			final Pair< RandomAccessibleInterval< DoubleType >, RandomAccessibleInterval< DoubleType > > solution ) throws IOException
	{
		if ( solution.getA() != null )
			saveSolutionComponent( dataProvider, iteration, scale, solution.getA(), scalingTermFilename );

		if ( solution.getB() != null )
			saveSolutionComponent( dataProvider, iteration, scale, solution.getB(), translationTermFilename );
	}

	private void saveSolutionComponent(
			final DataProvider dataProvider,
			final int iteration,
			final int scale,
			final RandomAccessibleInterval< DoubleType > solutionComponent, final String filename ) throws IOException
	{
		final ImagePlus imp = ImageJFunctions.wrap( solutionComponent, filename );
		final String path = PathResolver.get( solutionPath, "iter" + iteration, Integer.toString( scale ), filename );
		dataProvider.saveImage( imp, path );
	}


	private static long[] getMinTileSize( final TileInfo[] tiles )
	{
		final long[] minSize = tiles[ 0 ].getSize().clone();
		for ( final TileInfo tile : tiles )
			for ( int d = 0; d < minSize.length; d++ )
				if (minSize[ d ] > tile.getSize( d ))
				{
					minSize[ d ] = tile.getSize( d );
					System.out.println("  Tile min size: " + Arrays.toString( tile.getSize() ) + ", tile: " + tile.getFilePath() );
				}
		return minSize;
	}
}
