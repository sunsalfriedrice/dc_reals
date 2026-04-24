# dc_reels

DCInside 갤러리 링크를 입력해 개념글을 릴스처럼 넘겨보는 Android 샘플 앱입니다.

## 구현된 기능
- 갤러리 링크(PC/모바일 URL) 입력 후 갤러리 추가
- 단축 링크 입력 시 최종 리다이렉트 URL 해석 후 갤러리 ID 추출
- 링크 형식 오류 시 단일 토스트 문구 표시
- Drawer 사이드패널에서 갤러리 선택/이름 편집/삭제
- 삭제 직후 2초 동안 실행 취소(Snackbar)
- 세로 `ViewPager2` 기반 게시글 탐색
- 개념글 전용 피드(`recommend=1`) 로드
- 릴스 카드 중앙 이미지(없으면 아이콘) + 본문 미리보기
- 본문/이미지 탭 시 원문 형식 상세(WebView) 화면 이동
- 댓글 상세 화면에서 `최신순`/`오래된순` 정렬 전환
- 댓글 15개 단위 로드
- 크롤링 요청마다 0.3초~1.4초 랜덤 딜레이 적용

## 실행
```powershell
cd C:\Users\okse8\AndroidStudioProjects\dc_reels
.\gradlew.bat test
```

```powershell
cd C:\Users\okse8\AndroidStudioProjects\dc_reels
.\gradlew.bat assembleDebug
```

## 참고
- 댓글은 DC 페이지 HTML에서 추출 가능한 범위에서 파싱합니다.
- 사이트 마크업 변경 시 일부 파서가 동작하지 않을 수 있습니다.

