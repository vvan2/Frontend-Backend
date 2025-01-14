import cv2
import mediapipe as mp
import joblib
import numpy as np
from flask import Flask, request, jsonify
import base64
from io import BytesIO
from PIL import Image

# Flask 앱 초기화
app = Flask(__name__)

# Mediapipe Pose 초기화
mp_drawing = mp.solutions.drawing_utils
mp_pose = mp.solutions.pose

# 학습된 모델 로드
model = joblib.load('C:/Users/wndhk/vision/golf_pose_model.pkl')

# 랜드마크에서 각도 계산 함수
def extract_landmarks(image, pose):
    """이미지에서 랜드마크를 추출하고 1D 배열로 반환"""
    results = pose.process(image)
    landmarks = []
    if results.pose_landmarks:
        for landmark in results.pose_landmarks.landmark:
            landmarks.append([landmark.x, landmark.y, landmark.z])  # 각 랜드마크의 x, y, z 좌표
    return np.array(landmarks).flatten()

# 이미지를 base64로 디코딩하여 OpenCV 형식으로 변환
def decode_image(image_base64):
    img_data = base64.b64decode(image_base64)
    img = Image.open(BytesIO(img_data))
    return np.array(img)

# 비디오 스트림을 처리하는 함수
def process_video_stream(video_base64):
    """Base64 인코딩된 비디오 스트림을 받아 프레임별로 처리"""
    video_data = base64.b64decode(video_base64)
    np_data = np.frombuffer(video_data, np.uint8)
    video = cv2.imdecode(np_data, cv2.IMREAD_COLOR)
    
    # Mediapipe Pose 객체 초기화 (with 구문 밖에서 객체 생성)
    pose = mp_pose.Pose(
        static_image_mode=False,
        model_complexity=1,
        smooth_landmarks=True,
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5
    )
    
    # 비디오를 프레임 단위로 처리
    frame_rgb = cv2.cvtColor(video, cv2.COLOR_BGR2RGB)
    frame_rgb.flags.writeable = False

    results = pose.process(frame_rgb)

    if results.pose_landmarks:
        landmark_data = np.array([extract_landmarks(video, pose)])
        prediction = model.predict(landmark_data)
        pose_class = prediction[0]  # 예측된 동작 라벨

        if pose_class == 0:
            pose_status = "Driver"
        elif pose_class == 1:
            pose_status = "Putting"
        else:
            pose_status = "Iron"
        
        return pose_status
    else:
        return "No pose detected"

# Flask API 엔드포인트 정의
@app.route('/predict_pose', methods=['POST'])
def predict_pose():
    """골프 자세 예측 API"""
    try:
        # 클라이언트로부터 base64 인코딩된 이미지 또는 비디오 받기
        data = request.json
        image_base64 = data.get('image')
        video_base64 = data.get('video')

        if image_base64:
            # 이미지 예측 처리
            image = decode_image(image_base64)

            # Mediapipe 처리
            pose = mp_pose.Pose(
                static_image_mode=False,
                model_complexity=1,
                smooth_landmarks=True,
                min_detection_confidence=0.5,
                min_tracking_confidence=0.5
            )

            # BGR 이미지를 RGB로 변환
            image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            image_rgb.flags.writeable = False

            # Mediapipe 처리
            results = pose.process(image_rgb)

            # 이미지 다시 쓰기 가능 설정
            image_rgb.flags.writeable = True
            image_bgr = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR)

            # 랜드마크가 감지되었을 때
            if results.pose_landmarks:
                # 랜드마크 추출 및 예측
                landmark_data = np.array([extract_landmarks(image, pose)])
                prediction = model.predict(landmark_data)
                pose_class = prediction[0]  # 예측된 동작 라벨

                # 동작을 나타내는 텍스트 반환
                if pose_class == 0:
                    pose_status = "Driver"
                elif pose_class == 1:
                    pose_status = "Putting"
                else:
                    pose_status = "Iron"

                return jsonify({"pose": pose_status}), 200
            else:
                return jsonify({"error": "No pose landmarks detected"}), 400

        elif video_base64:
            # 비디오 예측 처리
            pose_status = process_video_stream(video_base64)
            return jsonify({"pose": pose_status}), 200

        else:
            return jsonify({"error": "No image or video provided"}), 400

    except Exception as e:
        return jsonify({"error": str(e)}), 500

# 서버 실행
if __name__ == '__main__':
    app.run(debug=False, host='0.0.0.0', port=5000)
