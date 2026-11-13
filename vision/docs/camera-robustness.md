# 카메라 인식 회귀 평가

연습 화면은 vision/testdata/kiosk/index.html을 사용합니다.

CameraRobustnessTest는 아메리카노 메뉴의 OCR 좌표를 화면 폭의 5%, 8%, 12%만큼 이동하고, 한 프레임의 인식 결과가 모두 사라지는 상황을 재현합니다. 이동 후 이전 좌표에 메뉴가 중복으로 되살아나지 않는지, 읽을 수 없는 프레임 후 두 프레임을 확인하고 안내를 재개하는지 검사합니다.

실행 명령은 다음과 같습니다.

    ./gradlew :vision:testDebugUnitTest

KioskFlowTest는 옵션 선택, 장바구니 추가, 결제 승인, 완료 화면 전환을 검사합니다. KioskOcrReplayTest는 저장된 OCR 관측값을 재생합니다.

## 이미지 실험

evaluate_camera_images.py는 원본 HTML에서 아메리카노를 클릭하고 결제 완료까지 진행하여 10개 화면을 캡처합니다. 각 화면에 원본, 약한 흐림(0.7px), 중간 흐림(1.2px), 평행 이동, 2.5도 기울기, 어두운 조명을 적용합니다. DOM은 화면 진행에만 사용하며 인식은 캡처 이미지에 수행합니다.

Windows 한국어 OCR로 60개 이미지를 평가했습니다. 사전에 정의한 핵심 문구 96개 중 원본 입력은 81개(84.38%), 1080×1920 확대와 unsharp mask 처리는 83개(86.46%)를 읽었습니다. 이 수치는 핵심 문구 포함 여부이며, 버튼 좌표 정확도 또는 Android 실기기 인식률이 아닙니다. 표본이 작고 별도 검증 세트가 없으므로 전처리는 앱에 적용하지 않았습니다. 관측 결과는 testdata/kiosk/camera-image-results.json에 저장했습니다.

Windows에서 pillow, playwright와 스크립트 상단에 열거한 winrt 패키지를 설치하고 다음 명령으로 재현합니다.

    python -m playwright install chromium
    python vision/tools/evaluate_camera_images.py --output camera-evaluation

앱에는 이동 후 오래된 버튼 좌표 중복 방지와, 시작부터 계속 흔들려도 900ms마다 OCR을 재시도하는 수정이 적용되었습니다. CameraRobustnessTest와 ShakeGateTest가 이 동작을 검사합니다.

이 작업은 반복 평가에 따른 인식 파이프라인 개선입니다. ML Kit 모델 가중치를 재학습하지 않았으며 실제 휴대폰의 흐림·원근·반사광에서의 성능 측정은 남아 있습니다.
