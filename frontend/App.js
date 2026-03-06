import React, { useState, useEffect, useRef } from 'react';
import { 
  StyleSheet, Text, View, Dimensions, FlatList, Image, ScrollView, 
  ActivityIndicator, SafeAreaView, StatusBar, TouchableOpacity, TextInput, Alert, Modal
} from 'react-native';
import axios from 'axios';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { WebView } from 'react-native-webview';
import { MessageCircle, Share2, User, Plus, X, ChevronLeft, Globe } from 'lucide-react-native';
import cheerio from 'react-native-cheerio';

const { width, height: SCREEN_HEIGHT } = Dimensions.get('window');

// 갤러리 타입 정의 (마이너, 일반, 미니)
const GALL_TYPES = ['mgallery/board', 'board', 'mini/board'];
const USER_AGENTS = [
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0'
];

const getRandUA = () => USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const getHeaders = (referer = 'https://www.dcinside.com/') => ({
    'User-Agent': getRandUA(),
    'Referer': referer,
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
});

// 단독 실행 가능한 내장 스크레이퍼
const INTERNAL_SCRAPER = {
    async fetchList(gallery, page = 1, offset = 0) {
        let foundPosts = [];
        let selectedPath = 'board';

        for (const path of GALL_TYPES) {
            const url = `https://gall.dcinside.com/${path}/lists/?id=${gallery}&page=${page}&exception_mode=recommend`;
            try {
                const res = await axios.get(url, { headers: getHeaders(), timeout: 8000 });
                const $ = cheerio.load(res.data);
                const posts = [];
                
                $('tr.ub-content').each((i, el) => {
                    const $row = $(el);
                    if ($row.hasClass('ub-notice')) return;
                    const numText = $row.find('td.gall_num').text().trim();
                    if (numText === '공지' || isNaN(parseInt(numText))) return;
                    
                    if ($row.find('.icon_notice').length > 0 || $row.find('.notice_icon').length > 0) return;

                    const titleLink = $row.find('td.gall_tit a').first();
                    const title = titleLink.clone().children().remove().end().text().trim() || titleLink.text().trim();
                    if (title && numText) posts.push({ id: numText, title, path });
                });

                if (posts.length > 0) {
                    foundPosts = posts;
                    selectedPath = path;
                    break;
                }
            } catch (e) { continue; }
        }

        if (foundPosts.length === 0) return { posts: [], page, offset: 0 };

        const BATCH_SIZE = 3; // 한 번에 3개까지만 요청
        const limitedPosts = foundPosts.slice(offset, offset + BATCH_SIZE);
        let nextOffset = offset + BATCH_SIZE;
        let nextPage = page;

        if (nextOffset >= foundPosts.length) {
            nextOffset = 0;
            nextPage += 1;
        }

        const enriched = [];
        for (const p of limitedPosts) {
            const detail = await this.getDetail(gallery, p.path, p.id);
            enriched.push({ ...p, detail });
            await sleep(300); // 각 요청 사이 최소 200ms 이상의 딜레이 (300ms 설정)
        }

        return { posts: enriched, page: nextPage, offset: nextOffset };
    },
// ... (getDetail remains same)

    async getDetail(gallery, path, id, retry = 0) {
        const url = `https://gall.dcinside.com/${path}/view/?id=${gallery}&no=${id}`;
        try {
            const res = await axios.get(url, { headers: getHeaders(`https://gall.dcinside.com/${path}/lists/?id=${gallery}`), timeout: 15000 });
            const $ = cheerio.load(res.data);
            const contentDiv = $('div.write_div');
            if (!contentDiv.length) throw new Error("Empty content");

            // 불필요한 요소 미리 제거 (속도 향상)
            contentDiv.find('script, style, iframe, adsense, .revenue_unit_wrap, .comment_wrap').remove();

            // 로딩 이미지(Lazy Load) 처리: src가 loading_img.gif인 경우 실제 이미지로 교체
            contentDiv.find('img').each((i, el) => {
                const $img = $(el);
                const realSrc = $img.attr('data-original') || $img.attr('data-src') || $img.attr('data-lazy-src');
                if (realSrc) {
                    let finalSrc = realSrc;
                    if (finalSrc.startsWith('//')) finalSrc = 'https:' + finalSrc;
                    else if (finalSrc.startsWith('/')) finalSrc = 'https://gall.dcinside.com' + finalSrc;
                    $img.attr('src', finalSrc);
                }
            });

            const images = [];
            const dccons = [];
            
            // 이미지 주소만 빠르게 추출
            contentDiv.find('img').each((i, el) => {
                const $img = $(el);
                let src = $img.attr('data-original') || $img.attr('data-src') || $img.attr('data-lazy-src') || $img.attr('src');
                if (!src) return;
                if (src.startsWith('//')) src = 'https:' + src;
                else if (src.startsWith('/')) src = 'https://gall.dcinside.com' + src;
                
                if (src.includes('dccon.php') || src.includes('/dccon/')) dccons.push(src);
                else if (!src.includes('loading_img.gif')) images.push(src);
            });

            const plainText = contentDiv.text().replace(/\s\s+/g, ' ').trim();
            const fullHtml = contentDiv.html();
            
            return { id, plainText, images, dccons, originUrl: url, fullHtml };
        } catch (e) {
            if (retry < 2) return this.getDetail(gallery, path, id, retry + 1);
            return { id, plainText: "내용을 불러올 수 없습니다.", images: [], dccons: [], originUrl: url, fullHtml: "" };
        }
    }
};

const PostItem = ({ item, onOpenDetail, onOpenWebView, onImagePress, itemHeight }) => {
  const detail = item.detail;
  const displayImages = detail?.images || [];
  const previewText = detail?.plainText || "";

  return (
    <View style={[styles.postContainer, { height: itemHeight }]}>
      <View style={[styles.imageSection, { height: itemHeight * 0.55 }]}>
        {displayImages.length > 0 ? (
          <FlatList
            data={displayImages}
            horizontal
            pagingEnabled
            showsHorizontalScrollIndicator={false}
            keyExtractor={(_, i) => i.toString()}
            renderItem={({ item: img, index }) => (
              <TouchableOpacity activeOpacity={0.9} onPress={() => onImagePress(displayImages, index)}>
                <Image source={{ uri: img, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }} style={{ width, height: itemHeight * 0.55 }} resizeMode="contain" />
              </TouchableOpacity>
            )}
          />
        ) : (
          <View style={[styles.image, styles.noImage, { height: itemHeight * 0.55 }]}><Text style={styles.noImageText}>이미지 없음</Text></View>
        )}
      </View>

      <View style={styles.contentSection}>
        <TouchableOpacity style={styles.textScrollArea} onPress={() => onOpenDetail(item)} activeOpacity={0.7}>
            <View style={styles.userInfo}>
                <View style={styles.avatar}><User color="white" size={18} /></View>
                <Text style={styles.titleText} numberOfLines={1}>{item.title}</Text>
            </View>
            <Text style={styles.bodyPreviewText} numberOfLines={3}>{previewText}</Text>
            <Text style={styles.seeMoreBtn}>...내용 전체 보기 (클릭)</Text>
        </TouchableOpacity>
      </View>

      <View style={styles.actions}>
        <TouchableOpacity onPress={() => onOpenWebView(item)} style={styles.actionBtn}><Globe color="white" size={28} /></TouchableOpacity>
        <TouchableOpacity onPress={() => onOpenDetail(item)} style={styles.actionBtn}><MessageCircle color="white" size={32} /></TouchableOpacity>
        <TouchableOpacity style={styles.actionBtn}><Share2 color="white" size={32} /></TouchableOpacity>
      </View>
    </View>
  );
};

export default function App() {
  const [galleries, setGalleries] = useState([{ id: 'manosaba', name: '마녀재판' }]);
  const [currentGallery, setCurrentGallery] = useState(null);
  const [posts, setPosts] = useState([]);
  const [page, setPage] = useState(1);
  const [offset, setOffset] = useState(0);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [loading, setLoading] = useState(false);
  const [listLayoutHeight, setListLayoutHeight] = useState(SCREEN_HEIGHT - 100);
  const [urlInput, setUrlInput] = useState('');
  const [showInput, setShowInput] = useState(false);
  const [selectedPost, setSelectedPost] = useState(null);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showWebViewModal, setShowWebViewModal] = useState(false);
  const [showImageModal, setShowImageModal] = useState(false);
  const [currentImages, setCurrentImages] = useState([]);
  const [initialImageIndex, setInitialImageIndex] = useState(0);

  const stateRef = useRef({ posts, loading, currentGallery, page, offset, currentIndex });
  useEffect(() => {
    stateRef.current = { posts, loading, currentGallery, page, offset, currentIndex };
  }, [posts, loading, currentGallery, page, offset, currentIndex]);

  const saveGalleries = async (list) => {
    try {
      await AsyncStorage.setItem('galleries', JSON.stringify(list));
    } catch (e) { console.error(e); }
  };

  const loadGalleries = async () => {
    try {
      const saved = await AsyncStorage.getItem('galleries');
      if (saved) {
        const list = JSON.parse(saved);
        setGalleries(list);
        if (list.length > 0) setCurrentGallery(list[0]);
      } else {
        setCurrentGallery(galleries[0]);
      }
    } catch (e) { console.error(e); }
  };

  useEffect(() => {
    loadGalleries();
  }, []);

  useEffect(() => {
    if (currentGallery) {
      setPosts([]);
      setPage(1);
      setOffset(0);
      setCurrentIndex(0); // 갤러리 변경 시 현재 인덱스 초기화
      fetchPosts(currentGallery.id, 1, 0, true);
    }
  }, [currentGallery]);

  const fetchPosts = async (galId, pageNum, currentOffset, refresh = false) => {
    if (loading || !galId) return;
    setLoading(true);
    try {
      const data = await INTERNAL_SCRAPER.fetchList(galId, pageNum, currentOffset);
      if (data && data.posts) {
        setPosts(prev => {
          const combined = refresh ? data.posts : [...prev, ...data.posts];
          
          // 중복 제거
          const uniquePosts = [];
          const seenIds = new Set();
          combined.forEach(p => {
            if (!seenIds.has(p.id)) {
              seenIds.add(p.id);
              uniquePosts.push(p);
            }
          });

          if (refresh) return uniquePosts; // 새로고침(또는 첫 진입) 시에는 슬라이싱 하지 않음

          // 슬라이싱 로직: 현재 보고 있는 글 이전의 글은 10개만 보존
          const startIdx = Math.max(0, stateRef.current.currentIndex - 10);
          return uniquePosts.slice(startIdx);
        });
        setPage(data.page);
        setOffset(data.offset);
      }
    } catch (e) {
      console.error("Fetch error:", e);
    }
    setLoading(false);
  };

  const handleImagePress = (images, index) => {
    setCurrentImages(images);
    setInitialImageIndex(index);
    setShowImageModal(true);
  };

  const addGallery = () => {
    if (!urlInput) return;
    let id = "";
    if (urlInput.includes("id=")) {
      id = urlInput.split("id=")[1].split("&")[0];
    } else {
      id = urlInput;
    }
    
    if (id) {
      const newGalleries = [...galleries, { id, name: id }];
      setGalleries(newGalleries);
      saveGalleries(newGalleries);
      setUrlInput("");
      setShowInput(false);
      Alert.alert("알림", `${id} 갤러리가 추가되었습니다.`);
    }
  };

  const deleteGallery = (id) => {
    Alert.alert("삭제", "이 갤러리를 삭제하시겠습니까?", [
      { text: "취소", style: "cancel" },
      { text: "삭제", style: "destructive", onPress: () => {
        const newGalleries = galleries.filter(g => g.id !== id);
        setGalleries(newGalleries);
        saveGalleries(newGalleries);
        if (currentGallery?.id === id) {
          setCurrentGallery(newGalleries.length > 0 ? newGalleries[0] : null);
        }
      }}
    ]);
  };

  const onViewableItemsChanged = useRef(({ viewableItems }) => {
    if (viewableItems.length > 0) {
      const newIndex = viewableItems[0].index;
      setCurrentIndex(newIndex);
      
      const { posts, loading, currentGallery, page, offset } = stateRef.current;
      // 현재 글 제외하고 3개 이하로 남았을 때 추가 로드 (총 3개 프리로드 유지)
      if (newIndex + 3 >= posts.length && !loading && currentGallery) {
        fetchPosts(currentGallery.id, page, offset);
      }
    }
  }).current;

  const renderSequentialContent = () => {
    if (!selectedPost || !selectedPost.detail || !selectedPost.detail.structuredBody) return null;
    const allImages = selectedPost.detail.images || [];

    return selectedPost.detail.structuredBody.map((item, index) => {
      if (item.type === 'text') {
        return <Text key={index} style={styles.detailText}>{item.content}</Text>;
      } else if (item.type === 'image') {
        const imgIndex = allImages.findIndex(url => url === item.content);
        return (
          <TouchableOpacity key={index} activeOpacity={0.9} onPress={() => handleImagePress(allImages, imgIndex >= 0 ? imgIndex : 0)}>
            <Image source={{ uri: item.content, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }} style={styles.detailImage} resizeMode="contain" />
          </TouchableOpacity>
        );
      } else if (item.type === 'dccon') {
        return (
          <View key={index} style={styles.dcconContainer}>
            <Image source={{ uri: item.content, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }} style={styles.dcconImage} resizeMode="contain" />
          </View>
        );
      }
      return null;
    });
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" />
      <View style={styles.headerContainer}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.headerScroll}>
          {galleries.map((g) => (
            <TouchableOpacity key={g.id} onPress={() => { setPosts([]); setCurrentGallery(g); setPage(1); setOffset(0); }} onLongPress={() => deleteGallery(g.id)} style={[styles.tab, currentGallery?.id === g.id && styles.activeTab]}>
              <Text style={[styles.tabText, currentGallery?.id === g.id && styles.activeTabText]}>{g.name}</Text>
            </TouchableOpacity>
          ))}
          <TouchableOpacity onPress={() => setShowInput(!showInput)} style={styles.tab}><Plus color="white" size={20} /></TouchableOpacity>
        </ScrollView>
        {showInput && (
            <View style={styles.inputArea}>
                <TextInput style={styles.input} value={urlInput} onChangeText={setUrlInput} placeholder="디시 주소" placeholderTextColor="#666" autoCapitalize="none" />
                <TouchableOpacity onPress={addGallery} style={styles.addBtn}><Text style={styles.addBtnText}>추가</Text></TouchableOpacity>
            </View>
        )}
      </View>

      <View style={{ flex: 1 }} onLayout={(e) => setListLayoutHeight(e.nativeEvent.layout.height)}>
        {loading && posts.length === 0 ? (
            <View style={styles.centerLoading}>
                <ActivityIndicator size="large" color="#fff" />
                <Text style={styles.loadingText}>갤러리 탐색 중...</Text>
            </View>
        ) : currentGallery && (
            <FlatList 
                data={posts} 
                renderItem={({ item }) => (
                <PostItem 
                    item={item} 
                    onOpenDetail={(p) => { setSelectedPost(p); setShowDetailModal(true); }} 
                    onOpenWebView={(p) => { setSelectedPost(p); setShowWebViewModal(true); }}
                    onImagePress={handleImagePress}
                    itemHeight={listLayoutHeight}
                />
                )} 
                keyExtractor={(item, index) => item.id.toString() + index} 
                pagingEnabled 
                showsVerticalScrollIndicator={false} 
                onViewableItemsChanged={onViewableItemsChanged}
                viewabilityConfig={{ itemVisiblePercentThreshold: 50 }}
                getItemLayout={(data, index) => ({ length: listLayoutHeight, offset: listLayoutHeight * index, index })}
                removeClippedSubviews={true}
                ListFooterComponent={loading && posts.length > 0 ? (
                    <View style={styles.footerLoading}>
                        <ActivityIndicator size="small" color="#fff" />
                    </View>
                ) : null}
            />
        )}
      </View>

      <Modal visible={showDetailModal} animationType="slide" onRequestClose={() => setShowDetailModal(false)}>
        <SafeAreaView style={styles.modalContent}>
          <View style={styles.modalHeader}>
            <TouchableOpacity onPress={() => setShowDetailModal(false)} style={styles.closeBtn}><ChevronLeft color="white" size={30} /></TouchableOpacity>
            <Text style={styles.modalTitle} numberOfLines={1}>{selectedPost?.title}</Text>
            <TouchableOpacity onPress={() => setShowDetailModal(false)}><X color="white" size={30} /></TouchableOpacity>
          </View>
          {selectedPost?.detail?.fullHtml ? (
            <WebView 
              originWhitelist={['*']}
              source={{ 
                html: `
                  <html>
                    <head>
                      <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                      <style>
                        body { 
                          background-color: #000; 
                          color: #eee; 
                          font-family: -apple-system, sans-serif; 
                          padding: 15px; 
                          line-height: 1.6;
                        }
                        img, video, iframe { 
                          max-width: 100% !important; 
                          height: auto !important; 
                          border-radius: 8px;
                          margin-bottom: 10px;
                        }
                        .write_div { width: 100% !important; }
                        a { color: #58a6ff; text-decoration: none; }
                        p { margin-bottom: 15px; }
                      </style>
                    </head>
                    <body>
                      <div class="write_div">${selectedPost.detail.fullHtml}</div>
                    </body>
                  </html>
                ` 
              }} 
              style={{ flex: 1, backgroundColor: '#000' }}
              textZoom={100}
            />
          ) : (
            <View style={styles.centerLoading}><ActivityIndicator color="white" /></View>
          )}
        </SafeAreaView>
      </Modal>

      <Modal visible={showWebViewModal} animationType="slide" onRequestClose={() => setShowWebViewModal(false)}>
        <SafeAreaView style={styles.modalContent}>
          <View style={styles.modalHeader}>
            <TouchableOpacity onPress={() => setShowWebViewModal(false)} style={styles.closeBtn}><ChevronLeft color="white" size={30} /></TouchableOpacity>
            <Text style={styles.modalTitle} numberOfLines={1}>원본 웹페이지</Text>
            <TouchableOpacity onPress={() => setShowWebViewModal(false)}><X color="white" size={30} /></TouchableOpacity>
          </View>
          {selectedPost && <WebView source={{ uri: selectedPost.detail?.originUrl }} style={{ flex: 1 }} startInLoadingState={true} renderLoading={() => <ActivityIndicator color="white" style={{ position: 'absolute', top: '50%', left: '50%' }} />} />}
        </SafeAreaView>
      </Modal>

      <Modal visible={showImageModal} transparent={true} animationType="fade" onRequestClose={() => setShowImageModal(false)}>
        <View style={styles.fullImageContainer}>
          <TouchableOpacity style={styles.imageCloseBtn} onPress={() => setShowImageModal(false)}><X color="white" size={35} /></TouchableOpacity>
          <FlatList data={currentImages} horizontal pagingEnabled initialScrollIndex={initialImageIndex} getItemLayout={(data, index) => ({ length: width, offset: width * index, index })} keyExtractor={(_, i) => i.toString()} renderItem={({ item }) => (<View style={styles.fullImageWrapper}><Image source={{ uri: item, headers: { 'Referer': 'https://gall.dcinside.com/', 'User-Agent': USER_AGENTS[0] } }} style={styles.fullImage} resizeMode="contain" /></View>)} />
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },
  headerContainer: { paddingVertical: 10, backgroundColor: 'black', borderBottomWidth: 1, borderBottomColor: '#222', paddingTop: 10 },
  headerScroll: { paddingHorizontal: 10 },
  tab: { paddingHorizontal: 15, paddingVertical: 8, marginHorizontal: 5, borderRadius: 20, backgroundColor: '#222' },
  activeTab: { backgroundColor: '#fff' },
  tabText: { color: '#ccc', fontWeight: 'bold' },
  activeTabText: { color: '#000' },
  inputArea: { margin: 10, backgroundColor: '#111', padding: 10, borderRadius: 10, flexDirection: 'row' },
  input: { flex: 1, color: '#fff' },
  addBtn: { backgroundColor: '#fff', paddingHorizontal: 15, borderRadius: 5, justifyContent: 'center' },
  addBtnText: { color: '#000', fontWeight: 'bold' },
  postContainer: { width, backgroundColor: '#000' },
  imageSection: { width, backgroundColor: '#000' },
  image: { width },
  noImage: { justifyContent: 'center', alignItems: 'center' },
  noImageText: { color: '#444' },
  contentSection: { padding: 20, flex: 1, backgroundColor: '#000' },
  userInfo: { flexDirection: 'row', alignItems: 'center', marginBottom: 10 },
  avatar: { width: 24, height: 24, borderRadius: 12, backgroundColor: '#333', justifyContent: 'center', alignItems: 'center', marginRight: 10 },
  titleText: { color: '#fff', fontSize: 17, fontWeight: 'bold', flex: 1 },
  bodyPreviewText: { color: '#aaa', fontSize: 14, marginTop: 8, lineHeight: 20 },
  textScrollArea: { flex: 1, marginTop: 5 },
  bodyText: { color: '#bbb', fontSize: 15, lineHeight: 22 },
  seeMoreBtn: { color: '#555', marginTop: 10, fontSize: 12, fontWeight: 'bold' },
  previewDcconContainer: { marginVertical: 5, alignItems: 'flex-start' },
  previewDccon: { width: 60, height: 60 },
  actions: { position: 'absolute', right: 15, bottom: 60 },
  actionBtn: { marginBottom: 20, backgroundColor: 'rgba(0,0,0,0.5)', padding: 10, borderRadius: 30 },
  centerLoading: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  loadingText: { color: '#fff', marginTop: 10, fontSize: 14 },
  footerLoading: { paddingVertical: 20, alignItems: 'center' },
  modalContent: { flex: 1, backgroundColor: '#000' },
  modalHeader: { flexDirection: 'row', alignItems: 'center', padding: 15, borderBottomWidth: 1, borderBottomColor: '#222' },
  modalTitle: { color: '#fff', fontSize: 16, fontWeight: 'bold', flex: 1, marginHorizontal: 10 },
  closeBtn: { padding: 5 },
  detailScroll: { flex: 1, padding: 15 },
  detailText: { color: '#eee', fontSize: 16, lineHeight: 24, marginBottom: 5 },
  detailImage: { width: width - 30, height: 300, marginBottom: 15, borderRadius: 5 },
  dcconContainer: { marginBottom: 10, alignItems: 'flex-start' },
  dcconImage: { width: 100, height: 100 },
  fullImageContainer: { flex: 1, backgroundColor: 'rgba(0,0,0,1)', justifyContent: 'center' },
  fullImageWrapper: { width, height: '100%', justifyContent: 'center', alignItems: 'center' },
  fullImage: { width: width, height: '100%' },
  imageCloseBtn: { position: 'absolute', top: 50, right: 20, zIndex: 10, padding: 10, backgroundColor: 'rgba(0,0,0,0.5)', borderRadius: 25 }
});
