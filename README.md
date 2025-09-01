## PKGem: Secure Enrichment of Personal Knowledge Graphs

Source code for PKGem, a system that provides an end-to-end secure solution to enrich personal knowledge graphs in mobile environments. The system is implemented as an Android application and supports a variety of real-world usage scenarios.

The paper is accepted by CIKM '25, [the video](https://youtu.be/MkXiGaZzmno) is available at Youtube.

## Dependencies

### System Requirements
- **Android SDK**: API level 26 (Android 8.0) or higher
- **Target SDK**: API level 34 (Android 14)
- **Gradle**: Version 8.12 (included in project)
- **Kotlin**: Version 1.8.0
- **Python**: Version 3.8 (for Chaquopy integration)

### Key Dependencies
- **Chaquopy**: Python runtime for Android (version 16.1.0)
- **TenSEAL**: Homomorphic encryption library (version 0.3.15)
- **PyTorch**: Machine learning framework (version 2.0.1)
- **Sentence Transformers**: Text embedding models (version 4.1.0)
- **ONNX Runtime**: Model inference optimization

### Python Packages
The following Python packages are automatically installed via Chaquopy:
- numpy==1.24.4
- scipy==1.15.3
- sentence_transformers==4.1.0
- tenseal==0.3.16
- torch==2.0.1
- torch_xla==2.7.0
- tqdm==4.66.1
- transformers==4.30.2
- psutil==5.9.5

## Installation

### Prerequisites
1. Install [Android Studio](https://developer.android.com/studio)
2. Install Android SDK with API level 26+ and target API level 34
3. Install Android NDK
4. Ensure you have at least 2GB of available RAM

### Clone Repository
```bash
git clone https://github.com/golden-eggs-lab/pkgem.git
cd pkgem
```


## Acknowledgement and License
PKGem is built on top of [TenSEAL](https://github.com/OpenMined/TenSEAL/tree/main), and the Python components are wrapped by [Chaquopy](https://github.com/chaquo/chaquopy).

[MIT LICENSE](LICENSE)

## Questions / Help / Bug Reports
If you encounter any problems while using PKGem and need our help, please [click here](https://github.com/golden-eggs-lab/pkgem/issues/new/choose) to report the problem.
