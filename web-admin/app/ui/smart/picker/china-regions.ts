// China administrative regions - compact built-in dataset
// Province (34) -> City (5-10 per province) -> District (3-5 per city)
// Consumers can inject a full dataset via AddressField's loadRegions prop.

export interface RegionNode {
  code: string;
  name: string;
  children?: RegionNode[];
}

export const REGIONS: RegionNode[] = [
  // ── Municipalities (直辖市) ──────────────────────────────────────────────
  {
    code: '110000',
    name: '北京市',
    children: [
      {
        code: '110100',
        name: '北京市',
        children: [
          { code: '110101', name: '东城区' },
          { code: '110102', name: '西城区' },
          { code: '110105', name: '朝阳区' },
          { code: '110106', name: '丰台区' },
          { code: '110108', name: '海淀区' },
        ],
      },
    ],
  },
  {
    code: '120000',
    name: '天津市',
    children: [
      {
        code: '120100',
        name: '天津市',
        children: [
          { code: '120101', name: '和平区' },
          { code: '120102', name: '河东区' },
          { code: '120103', name: '河西区' },
          { code: '120104', name: '南开区' },
          { code: '120105', name: '河北区' },
        ],
      },
    ],
  },
  {
    code: '310000',
    name: '上海市',
    children: [
      {
        code: '310100',
        name: '上海市',
        children: [
          { code: '310101', name: '黄浦区' },
          { code: '310104', name: '徐汇区' },
          { code: '310105', name: '长宁区' },
          { code: '310106', name: '静安区' },
          { code: '310110', name: '杨浦区' },
          { code: '310115', name: '浦东新区' },
        ],
      },
    ],
  },
  {
    code: '500000',
    name: '重庆市',
    children: [
      {
        code: '500100',
        name: '重庆市',
        children: [
          { code: '500101', name: '万州区' },
          { code: '500103', name: '涪陵区' },
          { code: '500106', name: '渝中区' },
          { code: '500107', name: '大渡口区' },
          { code: '500108', name: '江北区' },
          { code: '500109', name: '沙坪坝区' },
        ],
      },
    ],
  },

  // ── Provinces (省) ───────────────────────────────────────────────────────
  {
    code: '130000',
    name: '河北省',
    children: [
      {
        code: '130100',
        name: '石家庄市',
        children: [
          { code: '130102', name: '长安区' },
          { code: '130104', name: '桥西区' },
          { code: '130105', name: '新华区' },
          { code: '130107', name: '井陉矿区' },
        ],
      },
      {
        code: '130300',
        name: '秦皇岛市',
        children: [
          { code: '130302', name: '海港区' },
          { code: '130303', name: '山海关区' },
          { code: '130304', name: '北戴河区' },
        ],
      },
      {
        code: '130200',
        name: '唐山市',
        children: [
          { code: '130202', name: '路南区' },
          { code: '130203', name: '路北区' },
          { code: '130204', name: '古冶区' },
        ],
      },
    ],
  },
  {
    code: '140000',
    name: '山西省',
    children: [
      {
        code: '140100',
        name: '太原市',
        children: [
          { code: '140105', name: '小店区' },
          { code: '140106', name: '迎泽区' },
          { code: '140107', name: '杏花岭区' },
          { code: '140108', name: '尖草坪区' },
        ],
      },
      {
        code: '140200',
        name: '大同市',
        children: [
          { code: '140212', name: '新荣区' },
          { code: '140213', name: '平城区' },
          { code: '140214', name: '云冈区' },
        ],
      },
    ],
  },
  {
    code: '210000',
    name: '辽宁省',
    children: [
      {
        code: '210100',
        name: '沈阳市',
        children: [
          { code: '210102', name: '和平区' },
          { code: '210103', name: '沈河区' },
          { code: '210104', name: '大东区' },
          { code: '210105', name: '皇姑区' },
          { code: '210106', name: '铁西区' },
        ],
      },
      {
        code: '210200',
        name: '大连市',
        children: [
          { code: '210202', name: '中山区' },
          { code: '210203', name: '西岗区' },
          { code: '210204', name: '沙河口区' },
          { code: '210211', name: '甘井子区' },
        ],
      },
    ],
  },
  {
    code: '220000',
    name: '吉林省',
    children: [
      {
        code: '220100',
        name: '长春市',
        children: [
          { code: '220102', name: '南关区' },
          { code: '220103', name: '宽城区' },
          { code: '220104', name: '朝阳区' },
          { code: '220105', name: '二道区' },
        ],
      },
      {
        code: '220200',
        name: '吉林市',
        children: [
          { code: '220202', name: '昌邑区' },
          { code: '220203', name: '龙潭区' },
          { code: '220204', name: '船营区' },
        ],
      },
    ],
  },
  {
    code: '230000',
    name: '黑龙江省',
    children: [
      {
        code: '230100',
        name: '哈尔滨市',
        children: [
          { code: '230102', name: '道里区' },
          { code: '230103', name: '南岗区' },
          { code: '230104', name: '道外区' },
          { code: '230108', name: '平房区' },
          { code: '230109', name: '松北区' },
        ],
      },
      {
        code: '230200',
        name: '齐齐哈尔市',
        children: [
          { code: '230202', name: '龙沙区' },
          { code: '230203', name: '建华区' },
          { code: '230204', name: '铁锋区' },
        ],
      },
    ],
  },
  {
    code: '320000',
    name: '江苏省',
    children: [
      {
        code: '320100',
        name: '南京市',
        children: [
          { code: '320102', name: '玄武区' },
          { code: '320104', name: '秦淮区' },
          { code: '320105', name: '建邺区' },
          { code: '320106', name: '鼓楼区' },
          { code: '320111', name: '浦口区' },
        ],
      },
      {
        code: '320500',
        name: '苏州市',
        children: [
          { code: '320505', name: '虎丘区' },
          { code: '320506', name: '吴中区' },
          { code: '320507', name: '相城区' },
          { code: '320508', name: '姑苏区' },
        ],
      },
      {
        code: '320200',
        name: '无锡市',
        children: [
          { code: '320205', name: '锡山区' },
          { code: '320206', name: '惠山区' },
          { code: '320211', name: '滨湖区' },
        ],
      },
      {
        code: '320300',
        name: '徐州市',
        children: [
          { code: '320302', name: '鼓楼区' },
          { code: '320303', name: '云龙区' },
          { code: '320305', name: '贾汪区' },
        ],
      },
    ],
  },
  {
    code: '330000',
    name: '浙江省',
    children: [
      {
        code: '330100',
        name: '杭州市',
        children: [
          { code: '330102', name: '上城区' },
          { code: '330105', name: '拱墅区' },
          { code: '330106', name: '西湖区' },
          { code: '330108', name: '滨江区' },
          { code: '330110', name: '余杭区' },
        ],
      },
      {
        code: '330200',
        name: '宁波市',
        children: [
          { code: '330203', name: '海曙区' },
          { code: '330205', name: '江北区' },
          { code: '330206', name: '北仑区' },
          { code: '330211', name: '镇海区' },
        ],
      },
      {
        code: '330300',
        name: '温州市',
        children: [
          { code: '330302', name: '鹿城区' },
          { code: '330303', name: '龙湾区' },
          { code: '330304', name: '瓯海区' },
        ],
      },
    ],
  },
  {
    code: '340000',
    name: '安徽省',
    children: [
      {
        code: '340100',
        name: '合肥市',
        children: [
          { code: '340102', name: '瑶海区' },
          { code: '340103', name: '庐阳区' },
          { code: '340104', name: '蜀山区' },
          { code: '340111', name: '包河区' },
        ],
      },
      {
        code: '340300',
        name: '蚌埠市',
        children: [
          { code: '340302', name: '龙子湖区' },
          { code: '340303', name: '蚌山区' },
          { code: '340304', name: '禹会区' },
        ],
      },
    ],
  },
  {
    code: '350000',
    name: '福建省',
    children: [
      {
        code: '350100',
        name: '福州市',
        children: [
          { code: '350102', name: '鼓楼区' },
          { code: '350103', name: '台江区' },
          { code: '350104', name: '仓山区' },
          { code: '350105', name: '马尾区' },
        ],
      },
      {
        code: '350200',
        name: '厦门市',
        children: [
          { code: '350203', name: '思明区' },
          { code: '350205', name: '海沧区' },
          { code: '350206', name: '湖里区' },
          { code: '350211', name: '集美区' },
        ],
      },
    ],
  },
  {
    code: '360000',
    name: '江西省',
    children: [
      {
        code: '360100',
        name: '南昌市',
        children: [
          { code: '360102', name: '东湖区' },
          { code: '360103', name: '西湖区' },
          { code: '360104', name: '青云谱区' },
          { code: '360111', name: '青山湖区' },
        ],
      },
      {
        code: '360200',
        name: '景德镇市',
        children: [
          { code: '360202', name: '昌江区' },
          { code: '360203', name: '珠山区' },
        ],
      },
    ],
  },
  {
    code: '370000',
    name: '山东省',
    children: [
      {
        code: '370100',
        name: '济南市',
        children: [
          { code: '370102', name: '历下区' },
          { code: '370103', name: '市中区' },
          { code: '370104', name: '槐荫区' },
          { code: '370105', name: '天桥区' },
          { code: '370112', name: '历城区' },
        ],
      },
      {
        code: '370200',
        name: '青岛市',
        children: [
          { code: '370202', name: '市南区' },
          { code: '370203', name: '市北区' },
          { code: '370211', name: '黄岛区' },
          { code: '370212', name: '崂山区' },
        ],
      },
      {
        code: '370600',
        name: '烟台市',
        children: [
          { code: '370602', name: '芝罘区' },
          { code: '370611', name: '福山区' },
          { code: '370612', name: '牟平区' },
        ],
      },
    ],
  },
  {
    code: '410000',
    name: '河南省',
    children: [
      {
        code: '410100',
        name: '郑州市',
        children: [
          { code: '410102', name: '中原区' },
          { code: '410103', name: '二七区' },
          { code: '410104', name: '管城回族区' },
          { code: '410105', name: '金水区' },
          { code: '410106', name: '上街区' },
        ],
      },
      {
        code: '410600',
        name: '鹤壁市',
        children: [
          { code: '410602', name: '鹤山区' },
          { code: '410603', name: '山城区' },
          { code: '410611', name: '淇滨区' },
        ],
      },
      {
        code: '410300',
        name: '洛阳市',
        children: [
          { code: '410302', name: '老城区' },
          { code: '410303', name: '西工区' },
          { code: '410304', name: '瀍河区' },
          { code: '410305', name: '涧西区' },
        ],
      },
    ],
  },
  {
    code: '420000',
    name: '湖北省',
    children: [
      {
        code: '420100',
        name: '武汉市',
        children: [
          { code: '420102', name: '江岸区' },
          { code: '420103', name: '江汉区' },
          { code: '420104', name: '硚口区' },
          { code: '420105', name: '汉阳区' },
          { code: '420106', name: '武昌区' },
          { code: '420107', name: '青山区' },
          { code: '420111', name: '洪山区' },
        ],
      },
      {
        code: '420200',
        name: '黄石市',
        children: [
          { code: '420202', name: '黄石港区' },
          { code: '420203', name: '西塞山区' },
          { code: '420204', name: '下陆区' },
        ],
      },
      {
        code: '420500',
        name: '宜昌市',
        children: [
          { code: '420502', name: '西陵区' },
          { code: '420503', name: '伍家岗区' },
          { code: '420504', name: '点军区' },
        ],
      },
    ],
  },
  {
    code: '430000',
    name: '湖南省',
    children: [
      {
        code: '430100',
        name: '长沙市',
        children: [
          { code: '430102', name: '芙蓉区' },
          { code: '430103', name: '天心区' },
          { code: '430104', name: '岳麓区' },
          { code: '430105', name: '开福区' },
          { code: '430111', name: '雨花区' },
        ],
      },
      {
        code: '430200',
        name: '株洲市',
        children: [
          { code: '430202', name: '荷塘区' },
          { code: '430203', name: '芦淞区' },
          { code: '430204', name: '石峰区' },
        ],
      },
    ],
  },
  {
    code: '440000',
    name: '广东省',
    children: [
      {
        code: '440100',
        name: '广州市',
        children: [
          { code: '440103', name: '荔湾区' },
          { code: '440104', name: '越秀区' },
          { code: '440105', name: '海珠区' },
          { code: '440106', name: '天河区' },
          { code: '440111', name: '白云区' },
          { code: '440112', name: '黄埔区' },
          { code: '440113', name: '番禺区' },
        ],
      },
      {
        code: '440300',
        name: '深圳市',
        children: [
          { code: '440303', name: '罗湖区' },
          { code: '440304', name: '福田区' },
          { code: '440305', name: '南山区' },
          { code: '440306', name: '宝安区' },
          { code: '440307', name: '龙岗区' },
          { code: '440308', name: '盐田区' },
        ],
      },
      {
        code: '440400',
        name: '珠海市',
        children: [
          { code: '440402', name: '香洲区' },
          { code: '440403', name: '斗门区' },
          { code: '440404', name: '金湾区' },
        ],
      },
      {
        code: '440600',
        name: '佛山市',
        children: [
          { code: '440604', name: '禅城区' },
          { code: '440605', name: '南海区' },
          { code: '440606', name: '顺德区' },
          { code: '440607', name: '三水区' },
        ],
      },
      {
        code: '440800',
        name: '湛江市',
        children: [
          { code: '440802', name: '赤坎区' },
          { code: '440803', name: '霞山区' },
          { code: '440804', name: '坡头区' },
        ],
      },
    ],
  },
  {
    code: '450000',
    name: '广西壮族自治区',
    children: [
      {
        code: '450100',
        name: '南宁市',
        children: [
          { code: '450102', name: '兴宁区' },
          { code: '450103', name: '青秀区' },
          { code: '450105', name: '江南区' },
          { code: '450107', name: '西乡塘区' },
          { code: '450108', name: '良庆区' },
        ],
      },
      {
        code: '450200',
        name: '柳州市',
        children: [
          { code: '450202', name: '城中区' },
          { code: '450203', name: '鱼峰区' },
          { code: '450204', name: '柳南区' },
          { code: '450205', name: '柳北区' },
        ],
      },
      {
        code: '450300',
        name: '桂林市',
        children: [
          { code: '450302', name: '秀峰区' },
          { code: '450303', name: '叠彩区' },
          { code: '450304', name: '象山区' },
          { code: '450305', name: '七星区' },
        ],
      },
    ],
  },
  {
    code: '460000',
    name: '海南省',
    children: [
      {
        code: '460100',
        name: '海口市',
        children: [
          { code: '460105', name: '秀英区' },
          { code: '460106', name: '龙华区' },
          { code: '460107', name: '琼山区' },
          { code: '460108', name: '美兰区' },
        ],
      },
      {
        code: '460200',
        name: '三亚市',
        children: [
          { code: '460202', name: '海棠区' },
          { code: '460203', name: '吉阳区' },
          { code: '460204', name: '天涯区' },
          { code: '460205', name: '崖州区' },
        ],
      },
    ],
  },
  {
    code: '510000',
    name: '四川省',
    children: [
      {
        code: '510100',
        name: '成都市',
        children: [
          { code: '510104', name: '锦江区' },
          { code: '510105', name: '青羊区' },
          { code: '510106', name: '金牛区' },
          { code: '510107', name: '武侯区' },
          { code: '510108', name: '成华区' },
          { code: '510112', name: '龙泉驿区' },
        ],
      },
      {
        code: '510300',
        name: '自贡市',
        children: [
          { code: '510302', name: '自流井区' },
          { code: '510303', name: '贡井区' },
          { code: '510304', name: '大安区' },
        ],
      },
      {
        code: '510400',
        name: '攀枝花市',
        children: [
          { code: '510402', name: '东区' },
          { code: '510403', name: '西区' },
          { code: '510411', name: '仁和区' },
        ],
      },
      {
        code: '510600',
        name: '德阳市',
        children: [
          { code: '510603', name: '旌阳区' },
          { code: '510604', name: '罗江区' },
        ],
      },
    ],
  },
  {
    code: '520000',
    name: '贵州省',
    children: [
      {
        code: '520100',
        name: '贵阳市',
        children: [
          { code: '520102', name: '南明区' },
          { code: '520103', name: '云岩区' },
          { code: '520111', name: '花溪区' },
          { code: '520112', name: '乌当区' },
          { code: '520113', name: '白云区' },
        ],
      },
      {
        code: '520200',
        name: '六盘水市',
        children: [
          { code: '520201', name: '钟山区' },
          { code: '520203', name: '六枝特区' },
        ],
      },
    ],
  },
  {
    code: '530000',
    name: '云南省',
    children: [
      {
        code: '530100',
        name: '昆明市',
        children: [
          { code: '530102', name: '五华区' },
          { code: '530103', name: '盘龙区' },
          { code: '530111', name: '官渡区' },
          { code: '530112', name: '西山区' },
          { code: '530113', name: '东川区' },
        ],
      },
      {
        code: '530300',
        name: '曲靖市',
        children: [
          { code: '530302', name: '麒麟区' },
          { code: '530303', name: '沾益区' },
          { code: '530304', name: '马龙区' },
        ],
      },
    ],
  },
  {
    code: '610000',
    name: '陕西省',
    children: [
      {
        code: '610100',
        name: '西安市',
        children: [
          { code: '610102', name: '新城区' },
          { code: '610103', name: '碑林区' },
          { code: '610104', name: '莲湖区' },
          { code: '610111', name: '灞桥区' },
          { code: '610112', name: '未央区' },
          { code: '610113', name: '雁塔区' },
        ],
      },
      {
        code: '610200',
        name: '铜川市',
        children: [
          { code: '610202', name: '王益区' },
          { code: '610203', name: '印台区' },
          { code: '610204', name: '耀州区' },
        ],
      },
      {
        code: '610300',
        name: '宝鸡市',
        children: [
          { code: '610302', name: '渭滨区' },
          { code: '610303', name: '金台区' },
          { code: '610304', name: '陈仓区' },
        ],
      },
      {
        code: '610400',
        name: '咸阳市',
        children: [
          { code: '610402', name: '秦都区' },
          { code: '610403', name: '杨陵区' },
          { code: '610404', name: '渭城区' },
        ],
      },
    ],
  },
  {
    code: '620000',
    name: '甘肃省',
    children: [
      {
        code: '620100',
        name: '兰州市',
        children: [
          { code: '620102', name: '城关区' },
          { code: '620103', name: '七里河区' },
          { code: '620104', name: '西固区' },
          { code: '620105', name: '安宁区' },
          { code: '620111', name: '红古区' },
        ],
      },
      {
        code: '620200',
        name: '嘉峪关市',
        children: [
          { code: '620201', name: '雄关区' },
          { code: '620202', name: '镜铁区' },
          { code: '620203', name: '长城区' },
        ],
      },
    ],
  },
  {
    code: '630000',
    name: '青海省',
    children: [
      {
        code: '630100',
        name: '西宁市',
        children: [
          { code: '630102', name: '城东区' },
          { code: '630103', name: '城中区' },
          { code: '630104', name: '城西区' },
          { code: '630105', name: '城北区' },
        ],
      },
    ],
  },

  // ── Autonomous Regions (自治区) ──────────────────────────────────────────
  {
    code: '150000',
    name: '内蒙古自治区',
    children: [
      {
        code: '150100',
        name: '呼和浩特市',
        children: [
          { code: '150102', name: '回民区' },
          { code: '150103', name: '玉泉区' },
          { code: '150104', name: '赛罕区' },
          { code: '150105', name: '新城区' },
        ],
      },
      {
        code: '150200',
        name: '包头市',
        children: [
          { code: '150202', name: '东河区' },
          { code: '150203', name: '昆都仑区' },
          { code: '150204', name: '青山区' },
          { code: '150205', name: '石拐区' },
        ],
      },
    ],
  },
  {
    code: '540000',
    name: '西藏自治区',
    children: [
      {
        code: '540100',
        name: '拉萨市',
        children: [
          { code: '540102', name: '城关区' },
          { code: '540103', name: '堆龙德庆区' },
          { code: '540104', name: '达孜区' },
        ],
      },
    ],
  },
  {
    code: '640000',
    name: '宁夏回族自治区',
    children: [
      {
        code: '640100',
        name: '银川市',
        children: [
          { code: '640104', name: '兴庆区' },
          { code: '640105', name: '西夏区' },
          { code: '640106', name: '金凤区' },
        ],
      },
      {
        code: '640200',
        name: '石嘴山市',
        children: [
          { code: '640202', name: '大武口区' },
          { code: '640205', name: '惠农区' },
        ],
      },
    ],
  },
  {
    code: '650000',
    name: '新疆维吾尔自治区',
    children: [
      {
        code: '650100',
        name: '乌鲁木齐市',
        children: [
          { code: '650102', name: '天山区' },
          { code: '650103', name: '沙依巴克区' },
          { code: '650104', name: '新市区' },
          { code: '650105', name: '水磨沟区' },
          { code: '650106', name: '头屯河区' },
        ],
      },
      {
        code: '650200',
        name: '克拉玛依市',
        children: [
          { code: '650202', name: '独山子区' },
          { code: '650203', name: '克拉玛依区' },
          { code: '650204', name: '白碱滩区' },
          { code: '650205', name: '乌尔禾区' },
        ],
      },
    ],
  },

  // ── Special Administrative Regions (特别行政区) ─────────────────────────
  {
    code: '810000',
    name: '香港特别行政区',
    children: [
      {
        code: '810100',
        name: '香港岛',
        children: [
          { code: '810101', name: '中西区' },
          { code: '810102', name: '湾仔区' },
          { code: '810103', name: '东区' },
          { code: '810104', name: '南区' },
        ],
      },
      {
        code: '810200',
        name: '九龙',
        children: [
          { code: '810201', name: '油尖旺区' },
          { code: '810202', name: '深水埗区' },
          { code: '810203', name: '九龙城区' },
          { code: '810204', name: '黄大仙区' },
          { code: '810205', name: '观塘区' },
        ],
      },
    ],
  },
  {
    code: '820000',
    name: '澳门特别行政区',
    children: [
      {
        code: '820100',
        name: '澳门半岛',
        children: [
          { code: '820101', name: '花地玛堂区' },
          { code: '820102', name: '圣安多尼堂区' },
          { code: '820103', name: '大堂区' },
          { code: '820104', name: '望德堂区' },
          { code: '820105', name: '风顺堂区' },
        ],
      },
    ],
  },
];
