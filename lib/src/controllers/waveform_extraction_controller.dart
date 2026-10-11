part of 'player_controller.dart';

/// WaveformExtractionController is used for
/// extraction of waveform data as List\<double\>,
/// which can be used to show [AudioFileWaveforms]
///
/// It is used by [PlayerController] internally for handling data extraction,
/// in case you only want data yourself you can use it as
///
/// ```dart
///      final waveformExtraction = WaveformExtractionController();
///      final waveformData = await waveformExtraction.extractWaveformData(path: '../audioFile.mp3');
///
///      ...
///
///      AudioFileWaveforms(
///         ...
///         waveformData: waveformData,
///      ),
/// ```dart
class WaveformExtractionController {
  WaveformExtractionController() : _extractorKey = shortHash(UniqueKey());

  WaveformExtractionController._(this._extractorKey);

  final String _extractorKey;

  /// voimero: `PlayerController.playerKey` に対応する公開名。打ち切りの例外
  /// ([WaveformExtractionStoppedException.playerKey]) に載る key なので、
  /// 呼び出し側が自分の抽出と突き止められるようにする（VENDORED.md 変更 4）。
  String get playerKey => _extractorKey;

  final List<double> _waveformData = [];

  /// This returns waveform data which can be used by [AudioFileWaveforms]
  /// to display waveforms.
  List<double> get waveformData => _waveformData.toList();

  /// A stream to get current extracted waveform data. This stream will emit
  /// list of doubles which are waveform data point.
  Stream<List<double>> get onCurrentExtractedWaveformData =>
      PlatformStreams.instance.onCurrentExtractedWaveformData
          .filter(_extractorKey);

  /// A stream to get current progress of waveform extraction.
  Stream<double> get onExtractionProgress =>
      PlatformStreams.instance.onExtractionProgress.filter(_extractorKey);

  /// Extracts waveform data from provided audio file path.
  /// [noOfSamples] indicates number of extracted data points. This will
  /// determine number of bars in the waveform.
  ///
  /// This function will decode whole audio file and will calculate RMS
  /// according to provided number of samples. So it may take a while to fully
  /// decode audio file, specifically on android.
  ///
  /// For example, an audio file of 58 min and about 18 MB of size took about
  /// 4 minutes to decode on android while the same file took about 6-7 seconds
  /// on IOS.
  ///
  /// Providing less number if sample doesn't make a difference because it
  /// still have to decode whole file.
  ///
  /// [noOfSamples] defaults to 100 if both [noOfSamples] and [noOfSamplesPerSecond] are null.
  ///
  /// [noOfSamplesPerSecond] can be used as an alternative to [noOfSamples] to specify
  /// the number of samples per second of audio. The actual [noOfSamples] will
  /// be calculated as: noOfSamplesPerSecond * durationInSeconds.
  /// This is useful when the full duration is not known in advance.
  ///
  /// **Important**: Provide only ONE of [noOfSamples] OR [noOfSamplesPerSecond], not both.
  /// - To use fixed sample count: provide only [noOfSamples]
  /// - To use samples per second: provide only [noOfSamplesPerSecond]
  /// - If both are null, defaults to [noOfSamples] = 100
  Future<List<double>> extractWaveformData({
    required String path,
    int? noOfSamples,
    int? noOfSamplesPerSecond,
  }) async {
    // Validate that user doesn't provide both parameters
    assert(
      !(noOfSamples != null && noOfSamplesPerSecond != null),
      'Cannot provide both noOfSamples and noOfSamplesPerSecond. '
      'Use noOfSamples for fixed count OR noOfSamplesPerSecond for dynamic calculation based on duration.',
    );

    // Determine which sampling strategy to use
    final int actualNoOfSamples;
    if (noOfSamplesPerSecond != null) {
      // Get duration to calculate actual samples
      final duration = await AudioWaveformsInterface.instance.getDuration(
        _extractorKey,
        DurationType.max.index,
      );

      if (duration != null && duration > 0) {
        actualNoOfSamples = (noOfSamplesPerSecond * (duration / 1000)).round();
      } else {
        // Fallback if duration unavailable
        actualNoOfSamples = noOfSamplesPerSecond;
      }
    } else {
      // Use fixed sample count (default to 100 if not provided)
      actualNoOfSamples = noOfSamples ?? 100;
    }

    // voimero patch (VENDORED.md 変更 4): Android の stop() は打ち切りを
    // `result.error(extraction_stopped)` で返す。上流はここで何も返さなかったので
    // `await extractWaveformData` が一生解決しなかった。エラーのまま投げると
    // 「失敗したのか打ち切られたのか」が呼び出し側で区別できないので、打ち切りは
    // 専用の例外に変換する（**打ち切りは失敗ではない**ので、失敗用の snackbar や
    // Crashlytics に流さないためにも型で分けておく）。
    try {
      return await AudioWaveformsInterface.instance.extractWaveformData(
        key: _extractorKey,
        path: path,
        noOfSamples: actualNoOfSamples,
      );
    } on PlatformException catch (error) {
      if (error.code == Constants.extractionStopped) {
        throw WaveformExtractionStoppedException(_extractorKey);
      }
      rethrow;
    }
  }

  /// Stops current waveform extraction, if any.
  Future<void> stopWaveformExtraction() async {
    return await AudioWaveformsInterface.instance
        .stopWaveformExtraction(_extractorKey);
  }
}

/// 抽出が完了する前に止められたことを表す例外。
///
/// 送り主は [WaveformExtractionController.stopWaveformExtraction]（と、それを呼ぶ
/// `PlayerController.dispose`、および同じ playerKey への再抽出）で、「抽出結果が
/// **必要とされなくなった**」という意味しか持たない。失敗と違って音声ファイルは
/// 壊れていないので、そのまま再試行すれば成功する。
///
/// 打ち切りを失敗と区別できるようにしたのは、呼び出し側が失敗用の snackbar や
/// Crashlytics に流さないため。
///
/// iOS は stop で FlutterResult を解決しない（`WaveformExtractor.swift` が stop 時に
/// 返信しない）ので、この例外が飛んでくるのは Android のみ。iOS 側は今までどおり
/// Future が解決しないままなので、**プラットフォームで挙動が違う**ことは上流への
/// issue に書く。
class WaveformExtractionStoppedException implements Exception {
  WaveformExtractionStoppedException(this.playerKey);

  final String playerKey;

  @override
  String toString() => 'Waveform extraction for playerKey "$playerKey" was '
      'stopped before it completed. This is not a failure: the data was no '
      'longer needed.';
}
